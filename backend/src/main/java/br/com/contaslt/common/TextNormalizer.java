package br.com.contaslt.common;

import java.text.Normalizer;
import java.util.Locale;

/** Normaliza descrições de lançamentos para comparação e sugestão de categoria. */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /** Minúsculas, sem acentos, sem dígitos/pontuação e com espaços colapsados. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String s = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z\\s]", " ").replaceAll("\\s+", " ").trim();
        return s;
    }

    public static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
