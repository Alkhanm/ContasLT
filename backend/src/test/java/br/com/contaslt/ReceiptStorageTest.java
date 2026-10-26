package br.com.contaslt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import br.com.contaslt.common.BusinessException;
import br.com.contaslt.receipt.ReceiptStorage;

class ReceiptStorageTest {

    @Test
    void normalizesToJpegWithinMaxSide() throws Exception {
        BufferedImage big = new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(big, "png", png);

        var normalize = ReceiptStorage.class.getDeclaredMethod("normalize", byte[].class);
        normalize.setAccessible(true);
        byte[] jpeg = (byte[]) normalize.invoke(null, (Object) png.toByteArray());
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(out.getWidth()).isEqualTo(1600);
        assertThat(out.getHeight()).isEqualTo(1200);
        assertThat(jpeg[0] & 0xFF).isEqualTo(0xFF); // marcador JPEG
    }

    /** Foto 40x20: metade esquerda vermelha, direita azul; o EXIF diz como o celular segurou a câmera. */
    private static byte[] jpegWithOrientation(int orientation) throws Exception {
        BufferedImage img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 40; x++) {
            for (int y = 0; y < 20; y++) {
                img.setRGB(x, y, x < 20 ? 0xFF0000 : 0x0000FF);
            }
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", jpeg);
        byte[] plain = jpeg.toByteArray();
        byte[] app1 = {
            (byte) 0xFF, (byte) 0xE1, 0, 34,                 // APP1, tamanho (inclui os 2 bytes do tamanho)
            'E', 'x', 'i', 'f', 0, 0,
            'I', 'I', 0x2A, 0, 8, 0, 0, 0,                   // TIFF little-endian, IFD0 no offset 8
            1, 0,                                            // 1 entrada
            0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0, // Orientation, SHORT, count 1, valor
            0, 0, 0, 0,                                      // fim da cadeia de IFDs
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2);                              // SOI
        out.write(app1);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    private static byte[] normalize(byte[] original) throws Exception {
        var normalize = ReceiptStorage.class.getDeclaredMethod("normalize", byte[].class);
        normalize.setAccessible(true);
        return (byte[]) normalize.invoke(null, (Object) original);
    }

    private static boolean isRed(int rgb) {
        return ((rgb >> 16) & 0xFF) > 150 && (rgb & 0xFF) < 100;
    }

    @Test
    void readsExifOrientation() throws Exception {
        assertThat(ReceiptStorage.exifOrientation(jpegWithOrientation(6))).isEqualTo(6);
        assertThat(ReceiptStorage.exifOrientation(jpegWithOrientation(8))).isEqualTo(8);
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "jpeg", plain);
        assertThat(ReceiptStorage.exifOrientation(plain.toByteArray())).isEqualTo(1);
        assertThat(ReceiptStorage.exifOrientation("nao e jpeg".getBytes())).isEqualTo(1);
    }

    @Test
    void rotatesPhoneLandscapeFramesUpright() throws Exception {
        // Orientação 6: o quadro deve girar 90° no sentido horário, a borda esquerda (vermelha) vai para o topo.
        BufferedImage cw = ImageIO.read(new ByteArrayInputStream(normalize(jpegWithOrientation(6))));
        assertThat(cw.getWidth()).isEqualTo(20);
        assertThat(cw.getHeight()).isEqualTo(40);
        assertThat(isRed(cw.getRGB(10, 5))).isTrue();
        assertThat(isRed(cw.getRGB(10, 35))).isFalse();

        // Orientação 8: 90° anti-horário, a borda esquerda vai para baixo.
        BufferedImage ccw = ImageIO.read(new ByteArrayInputStream(normalize(jpegWithOrientation(8))));
        assertThat(ccw.getWidth()).isEqualTo(20);
        assertThat(isRed(ccw.getRGB(10, 35))).isTrue();
        assertThat(isRed(ccw.getRGB(10, 5))).isFalse();

        // Orientação 3: 180°, o vermelho vai para a direita.
        BufferedImage flipped = ImageIO.read(new ByteArrayInputStream(normalize(jpegWithOrientation(3))));
        assertThat(flipped.getWidth()).isEqualTo(40);
        assertThat(isRed(flipped.getRGB(30, 10))).isTrue();
        assertThat(isRed(flipped.getRGB(10, 10))).isFalse();

        // Sem EXIF nada muda.
        BufferedImage same = ImageIO.read(new ByteArrayInputStream(normalize(jpegWithOrientation(1))));
        assertThat(same.getWidth()).isEqualTo(40);
        assertThat(isRed(same.getRGB(10, 10))).isTrue();
    }

    @Test
    void rejectsNonImages() throws Exception {
        var normalize = ReceiptStorage.class.getDeclaredMethod("normalize", byte[].class);
        normalize.setAccessible(true);
        assertThatThrownBy(() -> {
            try {
                normalize.invoke(null, (Object) "nao e imagem".getBytes());
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(BusinessException.class);
        assertThat(ReceiptStorage.ownedBy(7L, "u7-" + java.util.UUID.randomUUID() + ".jpg")).isTrue();
        assertThat(ReceiptStorage.ownedBy(8L, "u7-" + java.util.UUID.randomUUID() + ".jpg")).isFalse();
        assertThat(ReceiptStorage.ownedBy(7L, "../etc/passwd")).isFalse();
    }
}
