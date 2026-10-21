package br.com.contaslt.receipt;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.springframework.stereotype.Component;

import br.com.contaslt.common.BusinessException;
import br.com.contaslt.config.AppProperties;

/** Guarda as imagens dos comprovantes em disco, já reduzidas a um tamanho bom para leitura e exibição. */
@Component
public class ReceiptStorage {

    /** Lado maior máximo da imagem guardada; suficiente para o modelo ler o cupom e leve para o celular. */
    static final int MAX_SIDE = 1600;

    private final Path dir;

    public ReceiptStorage(AppProperties props) {
        this.dir = Path.of(props.storage().dir(), "receipts");
    }

    /** Normaliza (JPEG, no máximo {@link #MAX_SIDE}px) e grava. Retorna o nome do arquivo, prefixado pelo usuário. */
    public Stored store(Long userId, byte[] original) {
        byte[] jpeg = normalize(original);
        String name = "u" + userId + "-" + UUID.randomUUID() + ".jpg";
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), jpeg);
        } catch (IOException e) {
            throw new BusinessException("Não foi possível guardar a imagem do comprovante");
        }
        return new Stored(name, jpeg);
    }

    public Optional<byte[]> load(Long userId, String name) {
        if (!ownedBy(userId, name)) {
            return Optional.empty();
        }
        Path file = dir.resolve(name);
        try {
            return Files.exists(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static boolean ownedBy(Long userId, String name) {
        return name != null && name.matches("u" + userId + "-[0-9a-f-]{36}\\.jpg");
    }

    static byte[] normalize(byte[] original) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(original));
        } catch (IOException e) {
            img = null;
        }
        if (img == null) {
            throw new BusinessException("Formato de imagem não suportado. Envie uma foto JPG ou PNG.");
        }
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

    public record Stored(String name, byte[] bytes) {
    }
}
