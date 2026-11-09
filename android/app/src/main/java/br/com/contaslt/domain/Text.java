package br.com.contaslt.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Normalização e limpeza de textos (porte de frontend/src/domain/text.ts). */
public final class Text {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_LETTER = Pattern.compile("[^a-z\\s]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private Text() {
    }

    /** Minúsculas, sem acentos, sem dígitos/pontuação e com espaços colapsados (como TextNormalizer do backend). */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = Normalizer.normalize(text, Normalizer.Form.NFD);
        s = MARKS.matcher(s).replaceAll("");
        s = s.toLowerCase(Locale.ROOT);
        s = NON_LETTER.matcher(s).replaceAll(" ");
        s = SPACES.matcher(s).replaceAll(" ");
        return trim(s);
    }

    /** trim do JavaScript: tira também espaços Unicode (NBSP etc.), não só os caracteres até U+0020. */
    public static String trim(String s) {
        if (s == null) {
            return null;
        }
        int start = 0;
        int end = s.length();
        while (start < end && isJsSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isJsSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isJsSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\uFEFF';
    }

    public static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String t = trim(text);
        return t.length() <= max ? t : t.substring(0, max);
    }

    public static String blankToNull(String s) {
        return isBlank(s) ? null : trim(s);
    }

    public static boolean isBlank(String s) {
        return s == null || trim(s).isEmpty();
    }

    /** Texto "verdadeiro" no sentido do JavaScript: não nulo e não vazio. */
    public static boolean present(String s) {
        return s != null && !s.isEmpty();
    }
}
