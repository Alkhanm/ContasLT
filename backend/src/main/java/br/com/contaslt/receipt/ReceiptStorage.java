package br.com.contaslt.receipt;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import br.com.contaslt.common.BusinessException;
import br.com.contaslt.config.AppProperties;

/**
 * Guarda as imagens dos comprovantes já reduzidas a um tamanho bom para leitura e exibição.
 * Em disco local por padrão; no Supabase Storage quando {@code app.storage.supabase.url} está definida
 * (o sistema de arquivos do Railway não sobrevive a um deploy).
 */
@Component
public class ReceiptStorage {

    private static final Logger log = LoggerFactory.getLogger(ReceiptStorage.class);

    /** Lado maior máximo da imagem guardada; suficiente para o modelo ler o cupom e leve para o celular. */
    static final int MAX_SIDE = 1600;

    private final ReceiptBlobStore store;

    public ReceiptStorage(AppProperties props) {
        AppProperties.Storage cfg = props.storage();
        this.store = cfg.usesSupabase()
            ? new SupabaseBlobStore(cfg.supabase().url(), cfg.supabase().key(), cfg.supabase().bucket())
            : new LocalBlobStore(Path.of(cfg.dir(), "receipts"));
        log.info("Comprovantes guardados em: {}", store);
    }

    /** Normaliza (JPEG, no máximo {@link #MAX_SIDE}px) e grava. Retorna o nome do arquivo, prefixado pelo usuário. */
    public Stored store(Long userId, BufferedImage image) {
        byte[] jpeg = toJpeg(image);
        String name = "u" + userId + "-" + UUID.randomUUID() + ".jpg";
        try {
            store.put(name, jpeg);
        } catch (IOException e) {
            log.warn("Falha ao guardar o comprovante {}: {}", name, e.toString());
            throw new BusinessException("Não foi possível guardar a imagem do comprovante");
        }
        return new Stored(name, jpeg);
    }

    /** Grava bytes já prontos (restauração de backup); o nome precisa ter o prefixo do usuário. */
    public void storeRaw(Long userId, String name, byte[] jpeg) throws IOException {
        if (!ownedBy(userId, name)) {
            throw new IllegalArgumentException("Nome de comprovante fora do padrão: " + name);
        }
        store.put(name, jpeg);
    }

    public Optional<byte[]> load(Long userId, String name) {
        if (!ownedBy(userId, name)) {
            return Optional.empty();
        }
        try {
            return store.get(name);
        } catch (IOException e) {
            log.warn("Falha ao ler o comprovante {}: {}", name, e.toString());
            return Optional.empty();
        }
    }

    /** Exclusão de conta: apaga todas as fotos do usuário. */
    public int deleteAll(Long userId) {
        try {
            return store.deleteByPrefix("u" + userId + "-");
        } catch (IOException e) {
            log.warn("Falha ao apagar os comprovantes do usuário {}: {}", userId, e.toString());
            throw new BusinessException("Não foi possível apagar as fotos dos comprovantes agora. Tente de novo em instantes.");
        }
    }

    public static boolean ownedBy(Long userId, String name) {
        return name != null && name.matches("u" + userId + "-[0-9a-f-]{36}\\.jpg");
    }

    static byte[] normalize(byte[] original) {
        return toJpeg(decode(original));
    }

    /** Decodifica a foto enviada já na posição de leitura, em tamanho original. */
    public static BufferedImage decode(byte[] original) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(original));
        } catch (IOException e) {
            img = null;
        }
        if (img == null) {
            throw new BusinessException("Formato de imagem não suportado. Envie uma foto JPG ou PNG.");
        }
        // Celulares gravam o quadro do sensor deitado e anotam a rotação no EXIF; o ImageIO ignora a anotação.
        // Sem isto a foto fica de lado e o modelo lê datas e valores errados.
        return applyOrientation(img, exifOrientation(original));
    }

    /** Reduz a no máximo {@link #MAX_SIDE}px e codifica em JPEG. */
    static byte[] toJpeg(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, nw, nh);
        g.drawImage(img, 0, 0, nw, nh, null);
        g.dispose();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.85f);
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(out, null, null), param);
        } catch (IOException e) {
            throw new BusinessException("Não foi possível processar a imagem");
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** Aplica a orientação EXIF (1 a 8) e devolve a imagem na posição normal de leitura. */
    static BufferedImage applyOrientation(BufferedImage img, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return img;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }                                  // espelhada
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }                               // 180°
            case 4 -> { t.translate(0, h); t.scale(1, -1); }                                  // de cabeça para baixo, espelhada
            case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); }                              // transposta
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }                           // 90° horário
            case 7 -> { t.translate(h, w); t.rotate(Math.PI / 2); t.scale(-1, 1); }           // transversa
            default -> { t.translate(0, w); t.rotate(-Math.PI / 2); }                         // 8: 90° anti-horário
        }
        boolean swap = orientation >= 5;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(img, t, null);
        g.dispose();
        return out;
    }

    /** Lê a tag Orientation (0x0112) do EXIF de um JPEG. Devolve 1 (normal) quando ausente ou ilegível. */
    public static int exifOrientation(byte[] d) {
        if (d.length < 4 || (d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xD8) {
            return 1;
        }
        int i = 2;
        while (i + 4 <= d.length && (d[i] & 0xFF) == 0xFF) {
            int marker = d[i + 1] & 0xFF;
            if (marker == 0xFF) {                       // byte de preenchimento
                i++;
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;                                 // marcadores sem corpo
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) {     // começo dos dados da imagem / fim: EXIF não vem depois
                break;
            }
            int len = u16(d, i + 2, false);
            int body = i + 4;
            int end = Math.min(d.length, i + 2 + len);
            if (marker == 0xE1 && body + 6 <= end && d[body] == 'E' && d[body + 1] == 'x' && d[body + 2] == 'i'
                && d[body + 3] == 'f' && d[body + 4] == 0 && d[body + 5] == 0) {
                return orientationFromTiff(d, body + 6, end);
            }
            i += 2 + len;
        }
        return 1;
    }

    private static int orientationFromTiff(byte[] d, int start, int end) {
        if (start + 8 > end) {
            return 1;
        }
        boolean le;
        if (d[start] == 'I' && d[start + 1] == 'I') {
            le = true;
        } else if (d[start] == 'M' && d[start + 1] == 'M') {
            le = false;
        } else {
            return 1;
        }
        long ifdOffset = u32(d, start + 4, le);
        if (ifdOffset < 8 || start + ifdOffset + 2 > end) {
            return 1;
        }
        int p = (int) (start + ifdOffset);
        int entries = u16(d, p, le);
        p += 2;
        for (int k = 0; k < entries && p + 12 <= end; k++, p += 12) {
            if (u16(d, p, le) == 0x0112) {
                int value = u16(d, p + 8, le);          // tipo SHORT: o valor ocupa os 2 primeiros bytes do campo
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] d, int i, boolean le) {
        int a = d[i] & 0xFF;
        int b = d[i + 1] & 0xFF;
        return le ? (b << 8) | a : (a << 8) | b;
    }

    private static long u32(byte[] d, int i, boolean le) {
        long lo = u16(d, i, le);
        long hi = u16(d, i + 2, le);
        return le ? (hi << 16) | lo : (lo << 16) | hi;
    }

    public record Stored(String name, byte[] bytes) {
    }
}
