package br.com.contaslt.domain;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Leitor de extratos OFX (SGML 1.x e XML 2.x), porte de frontend/src/domain/ofx.ts. Trabalha por expressões regulares
 * sobre os blocos STMTTRN, o que tolera os arquivos "quase válidos" que os bancos brasileiros costumam gerar.
 */
public final class Ofx {

    private static final Pattern TRANSACTION =
        Pattern.compile("<STMTTRN>([\\s\\S]*?)(</STMTTRN>|(?=<STMTTRN>)|\\z)", Pattern.CASE_INSENSITIVE);
    /** {@code \s} do Java (sem os espaços Unicode do JavaScript). */
    private static final Pattern JAVA_SPACES = Pattern.compile("[ \\t\\n\\x0B\\f\\r]+");
    private static final Pattern OFX_DATE = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$");
    private static final Pattern DECIMAL =
        Pattern.compile("^([+-]?)(?:(\\d+)(?:\\.(\\d*))?|\\.(\\d+))(?:[eE]([+-]?\\d+))?$");
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;

    private Ofx() {
    }

    public static List<ParsedRow> parseOfx(byte[] input) {
        String text = decode(input);
        if (!text.toUpperCase(Locale.ROOT).contains("<OFX")) {
            throw new ParseException("O arquivo não parece ser um extrato OFX válido");
        }
        List<ParsedRow> rows = new ArrayList<>();
        java.util.regex.Matcher m = TRANSACTION.matcher(text);
        while (m.find()) {
            String block = m.group(1);
            String amountText = tag(block, "TRNAMT");
            String dateText = tag(block, "DTPOSTED");
            if (amountText == null || dateText == null || dateText.length() < 8) {
                continue;
            }
            Long amount = parseAmount(amountText);
            if (amount == null || amount == 0) {
                continue;
            }
            String date = parseOfxDate(dateText.substring(0, 8));
            String memo = tag(block, "MEMO");
            String name = tag(block, "NAME");
            String description = memo != null && !Text.trim(memo).isEmpty() ? memo : name;
            if (description == null || Text.trim(description).isEmpty()) {
                String trnType = tag(block, "TRNTYPE");
                description = trnType != null ? trnType : "Lançamento";
            }
            String fitId = tag(block, "FITID");
            rows.add(new ParsedRow(
                date,
                Text.truncate(JAVA_SPACES.matcher(description).replaceAll(" "), 140),
                Math.abs(amount),
                amount < 0 ? "EXPENSE" : "INCOME",
                fitId == null || Text.trim(fitId).isEmpty() ? null : Text.truncate(fitId, 120),
                null));
        }
        if (rows.isEmpty()) {
            throw new ParseException("Nenhum lançamento encontrado no arquivo OFX");
        }
        return rows;
    }

    /** {@code AAAAMMDD}, como LocalDate.parse com o padrão yyyyMMdd. */
    private static String parseOfxDate(String s) {
        java.util.regex.Matcher m = OFX_DATE.matcher(s);
        String date = m.matches()
            ? smartDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)))
            : null;
        if (date == null) {
            throw new ParseException("Data inválida no arquivo OFX: \"" + s + "\"");
        }
        return date;
    }

    /**
     * Data como o ResolverStyle.SMART do java.time: mês 1–12 e dia 1–31; dia além do fim do mês vira o último dia
     * (31/02 → 28 ou 29/02). {@code null} se fora disso.
     */
    public static String smartDate(int y, int mo, int d) {
        if (mo < 1 || mo > 12 || d < 1 || d > 31) {
            return null;
        }
        return Dates.ymd(y, mo, Math.min(d, Dates.lengthOfMonth(y, mo)));
    }

    /**
     * Valor em formato brasileiro ou americano ("-1.234,56", "1,234.56", "-50,00", "4500.00"), em centavos com
     * arredondamento HALF_UP. {@code null} se não for um número.
     */
    public static Long parseAmount(String raw) {
        String s = Text.trim(raw).replace(" ", "").replace("−", "-");
        if (s.contains(",") && s.contains(".")) {
            s = s.lastIndexOf(',') > s.lastIndexOf('.') ? s.replace(".", "").replace(",", ".") : s.replace(",", "");
        } else if (s.contains(",")) {
            s = s.replace(",", ".");
        }
        return decimalToCents(s);
    }

    /** Texto no formato aceito por {@code new BigDecimal(String)} → centavos (HALF_UP), sem ponto flutuante. */
    public static Long decimalToCents(String s) {
        java.util.regex.Matcher m = DECIMAL.matcher(s);
        if (!m.matches()) {
            return null;
        }
        String intPart = m.group(2) != null ? m.group(2) : "";
        String fracPart = m.group(3) != null ? m.group(3) : m.group(4) != null ? m.group(4) : "";
        int exp = 0;
        if (m.group(5) != null) {
            try {
                exp = Integer.parseInt(m.group(5).startsWith("+") ? m.group(5).substring(1) : m.group(5));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (Math.abs(exp) > 30) {
            return null;
        }
        String all = intPart + fracPart;
        BigInteger digits = new BigInteger(all.isEmpty() ? "0" : all);
        // valor = digits × 10^(exp − casas); centavos = valor × 100
        int scale = fracPart.length() - exp - 2;
        BigInteger cents;
        if (scale <= 0) {
            cents = digits.multiply(BigInteger.TEN.pow(-scale));
        } else {
            BigInteger div = BigInteger.TEN.pow(scale);
            BigInteger[] qr = digits.divideAndRemainder(div);
            cents = qr[0];
            if (qr[1].shiftLeft(1).compareTo(div) >= 0) {
                cents = cents.add(BigInteger.ONE);
            }
        }
        if (cents.bitLength() > 63 || cents.longValue() > MAX_SAFE_INTEGER) {
            return null;
        }
        long n = cents.longValue();
        return "-".equals(m.group(1)) ? -n : n;
    }

    private static String tag(String block, String name) {
        java.util.regex.Matcher m = Pattern.compile("<" + name + ">\\s*([^<\\r\\n]*)", Pattern.CASE_INSENSITIVE)
            .matcher(block);
        return m.find() ? Text.trim(m.group(1)) : null;
    }

    /** ISO-8859-1 exato: cada byte vira o caractere de mesmo código. */
    public static String latin1(byte[] bytes, int max) {
        int end = Math.min(bytes.length, max);
        return new String(bytes, 0, end, StandardCharsets.ISO_8859_1);
    }

    private static String decode(byte[] bytes) {
        String header = latin1(bytes, 600).toUpperCase(Locale.ROOT);
        if (header.contains("CHARSET:1252") || header.contains("ENCODING:USASCII")
            || header.contains("ENCODING=\"ISO-8859-1\"") || header.contains("ENCODING=\"WINDOWS-1252\"")) {
            return windows1252(bytes);
        }
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            // O TextDecoder do navegador descarta o BOM.
            return s.startsWith("\uFEFF") ? s.substring(1) : s;
        } catch (CharacterCodingException e) {
            return windows1252(bytes);
        }
    }

    /**
     * windows-1252 como o TextDecoder do navegador (WHATWG): os 5 bytes sem caractere definido (0x81, 0x8D, 0x8F,
     * 0x90, 0x9D) viram o código de mesmo valor, em vez de U+FFFD.
     */
    public static String windows1252(byte[] bytes) {
        String s = new String(bytes, Charset.forName("windows-1252"));
        if (s.length() != bytes.length) {
            return s;
        }
        StringBuilder sb = null;
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0x81 || b == 0x8D || b == 0x8F || b == 0x90 || b == 0x9D) {
                if (sb == null) {
                    sb = new StringBuilder(s);
                }
                sb.setCharAt(i, (char) b);
            }
        }
        return sb == null ? s : sb.toString();
    }
}
