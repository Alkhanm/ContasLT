package br.com.contaslt;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;

/** AccessKey é package-private no pacote receipt; o teste chega nela por reflexão. */
class AccessKeyTest {

    /** Chave real de uma NFC-e de Goiás emitida em outubro de 2026 (AAMM = 2610). */
    static final String KEY = "52261037260502000161650110006984791933601663";

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Class<?> c = Class.forName("br.com.contaslt.receipt.AccessKey");
        Method m = c.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    @Test
    void validatesCheckDigit() throws Exception {
        assertThat((boolean) call("isValid", new Class<?>[] {String.class}, KEY)).isTrue();
        String wrongDigit = KEY.substring(0, 43) + (KEY.charAt(43) == '0' ? '1' : '0');
        assertThat((boolean) call("isValid", new Class<?>[] {String.class}, wrongDigit)).isFalse();
        assertThat((boolean) call("isValid", new Class<?>[] {String.class}, "522610151973230001996510261103303742152261055792297")).isFalse(); // 51 dígitos inventados
        assertThat((boolean) call("isValid", new Class<?>[] {String.class}, (Object) null)).isFalse();
    }

    @Test
    void fixesYearFromKeyWhenMonthMatches() throws Exception {
        Class<?>[] types = {LocalDate.class, String.class};
        assertThat(call("fixYear", types, LocalDate.of(2025, 10, 25), KEY)).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(call("fixYear", types, LocalDate.of(2026, 10, 25), KEY)).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(call("fixYear", types, LocalDate.of(2025, 9, 25), KEY)).isEqualTo(LocalDate.of(2025, 9, 25)); // mês diferente: não mexe
        assertThat(call("fixYear", types, LocalDate.of(2025, 10, 25), null)).isEqualTo(LocalDate.of(2025, 10, 25));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsKeyFromQrCode() throws Exception {
        String url = "http://www.sefaz.go.gov.br/nfce/qr?p=" + KEY + "|2|1|1|ABCDEF0123456789";
        var matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 300, 300);
        BufferedImage qr = MatrixToImageWriter.toBufferedImage(matrix);
        // QR pequeno no canto de uma "foto" maior, como num cupom.
        BufferedImage photo = new BufferedImage(1200, 1600, BufferedImage.TYPE_INT_RGB);
        var g = photo.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 1200, 1600);
        g.drawImage(qr, 450, 1200, null);
        g.dispose();

        Optional<String> found = (Optional<String>) call("decodeQr", new Class<?>[] {BufferedImage.class}, photo);
        assertThat(found).contains(KEY);

        BufferedImage blank = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        assertThat((Optional<String>) call("decodeQr", new Class<?>[] {BufferedImage.class}, blank)).isEmpty();
    }
}
