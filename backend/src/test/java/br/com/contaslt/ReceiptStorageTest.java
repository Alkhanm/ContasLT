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
