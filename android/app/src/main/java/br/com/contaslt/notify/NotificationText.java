package br.com.contaslt.notify;

import java.util.ArrayList;
import java.util.List;

/** Junta os pedaços de texto de uma notificação sem repetir (Java puro, testável na JVM). */
final class NotificationText {

    private NotificationText() {
    }

    /**
     * Pedaços na ordem dada, sem vazios, sem os iguais ao título e sem os que já estão contidos em outro (o texto
     * curto costuma ser o começo do expandido). Devolve null se não sobrar nada.
     */
    static String join(String title, List<String> parts) {
        List<String> kept = new ArrayList<>();
        String t = title == null ? "" : norm(title);
        for (String p : parts) {
            if (p == null) {
                continue;
            }
            String s = p.trim();
            String ns = norm(s);
            if (ns.isEmpty() || ns.equals(t)) {
                continue;
            }
            boolean covered = false;
            for (int i = 0; i < kept.size(); i++) {
                String nk = norm(kept.get(i));
                if (nk.contains(ns)) {
                    covered = true;
                    break;
                }
                if (ns.contains(nk)) {
                    kept.set(i, s);
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                kept.add(s);
            }
        }
        // Um pedaço que cresceu pode ter passado a conter outro já guardado.
        List<String> out = new ArrayList<>();
        for (String s : kept) {
            boolean dup = false;
            for (String o : out) {
                if (norm(o).contains(norm(s))) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out.add(s);
            }
        }
        return out.isEmpty() ? null : String.join("\n", out);
    }

    /** Espaços colapsados e "…" das notificações truncadas removido, para comparar. */
    private static String norm(String s) {
        String n = s.replaceAll("\\s+", " ").trim();
        if (n.endsWith("…") || n.endsWith("...")) {
            n = n.replaceAll("(…|\\.\\.\\.)$", "").trim();
        }
        return n;
    }
}
