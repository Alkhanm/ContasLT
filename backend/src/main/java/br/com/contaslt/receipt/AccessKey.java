package br.com.contaslt.receipt;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.ReaderException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

/**
 * Chave de acesso da NFC-e (44 dígitos): leitura pelo QR code impresso no cupom, validação do dígito
 * verificador e extração do ano/mês de emissão (posições 3 a 6, no formato AAMM).
 */
final class AccessKey {

    private static final Pattern KEY = Pattern.compile("(?<!\\d)(\\d{44})(?!\\d)");
    private static final Map<DecodeHintType, Object> HINTS = Map.of(
        DecodeHintType.TRY_HARDER, Boolean.TRUE,
        DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));

    private AccessKey() {
    }

    /** Procura um QR code na foto e devolve a chave de 44 dígitos contida nele, quando o dígito verificador confere. */
    static Optional<String> decodeQr(BufferedImage image) {
        for (BufferedImage candidate : candidates(image)) {
            BufferedImageLuminanceSource source = new BufferedImageLuminanceSource(candidate);
            for (BinaryBitmap bitmap : List.of(new BinaryBitmap(new HybridBinarizer(source)),
                    new BinaryBitmap(new GlobalHistogramBinarizer(source)))) {
                String text;
                try {
                    text = new QRCodeReader().decode(bitmap, HINTS).getText();
                } catch (ReaderException e) {
                    continue;
                }
                Matcher m = KEY.matcher(text);
                while (m.find()) {
                    if (isValid(m.group(1))) {
                        return Optional.of(m.group(1));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** A foto inteira e versões reduzidas: um QR grande ou borrado às vezes só decodifica em escala menor. */
    private static List<BufferedImage> candidates(BufferedImage image) {
        List<BufferedImage> out = new ArrayList<>();
        out.add(image);
        int max = Math.max(image.getWidth(), image.getHeight());
        for (int side : new int[] {1600, 1000}) {
            if (max > side) {
                out.add(scale(image, (double) side / max));
            }
        }
        return out;
    }

    private static BufferedImage scale(BufferedImage img, double factor) {
        int w = Math.max(1, (int) Math.round(img.getWidth() * factor));
        int h = Math.max(1, (int) Math.round(img.getHeight() * factor));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** 44 dígitos com o dígito verificador (módulo 11, pesos 2 a 9) correto. */
    static boolean isValid(String key) {
        if (key == null || !key.matches("\\d{44}")) {
            return false;
        }
        int sum = 0;
        int weight = 2;
        for (int i = 42; i >= 0; i--) {
            sum += (key.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int dv = 11 - (sum % 11);
        if (dv >= 10) {
            dv = 0;
        }
        return key.charAt(43) - '0' == dv;
    }

    /**
     * O modelo de visão às vezes erra o ano (lê 2025 onde está 2026). Quando a chave confirma o mês lido,
     * o ano passa a ser o da chave.
     */
    static LocalDate fixYear(LocalDate date, String key) {
        if (date == null || !isValid(key)) {
            return date;
        }
        int year = 2000 + Integer.parseInt(key.substring(2, 4));
        int month = Integer.parseInt(key.substring(4, 6));
        if (month == date.getMonthValue() && year != date.getYear()) {
            return date.withYear(year);
        }
        return date;
    }
}
