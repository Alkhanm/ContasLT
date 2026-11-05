package br.com.contaslt.notify;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê o texto de uma notificação de app de banco e extrai um lançamento: valor, tipo (despesa/receita), forma
 * (cartão, Pix, boleto, outro), estabelecimento ou pessoa, final do cartão e data. Java puro (testável na JVM).
 *
 * <p>Funciona com os textos comuns dos bancos brasileiros ("Compra de R$ 45,90 APROVADA em PADARIA X para o cartão
 * com final 1234", "Você recebeu um Pix de R$ 200,00 de Fulano", "Compra aprovada no cartão final 1234: R$ 45,90 em
 * LOJA em 05/11 às 10:21"...). O que não for reconhecido devolve null e fica na lista "Não reconhecidas".
 */
public final class BankParser {

    public enum Kind { CARD, PIX, BOLETO, TRANSFER, OTHER }

    /** Resultado da leitura. Valor em centavos; data AAAA-MM-DD. */
    public static final class Parsed {
        public final String type;
        public final long amount;
        public final Kind kind;
        public final String counterpart;
        public final String cardLast4;
        public final String date;

        Parsed(String type, long amount, Kind kind, String counterpart, String cardLast4, String date) {
            this.type = type;
            this.amount = amount;
            this.kind = kind;
            this.counterpart = counterpart;
            this.cardLast4 = cardLast4;
            this.date = date;
        }

        /** Descrição para o lançamento: o estabelecimento/pessoa em "Título Próprio", ou a forma de pagamento. */
        public String description() {
            if (counterpart != null) {
                return titleCase(counterpart);
            }
            return switch (kind) {
                case PIX -> "INCOME".equals(type) ? "Pix recebido" : "Pix enviado";
                case BOLETO -> "Pagamento de boleto";
                case TRANSFER -> "INCOME".equals(type) ? "Transferência recebida" : "Transferência enviada";
                case CARD -> "Compra no cartão";
                default -> "INCOME".equals(type) ? "Recebimento" : "Pagamento";
            };
        }
    }

    private static final Pattern AMOUNT = Pattern.compile("R\\$\\s*(-?\\d{1,3}(?:\\.\\d{3})*(?:,\\d{1,2})?|-?\\d+(?:,\\d{1,2})?)");
    private static final Pattern LAST4 = Pattern.compile("(?:final|terminado em|término|\\*{2,4})\\s*(\\d{4})");
    private static final Pattern DATE = Pattern.compile("\\b(\\d{2})/(\\d{2})(?:/(\\d{2,4}))?\\b");

    /** Textos que não são movimento (códigos, avisos de fatura, propaganda, compra negada). */
    private static final String[] IGNORE = {
        "codigo", "token", "senha", "fatura fechou", "fatura fechada", "fatura esta disponivel", "fatura disponivel",
        "fatura vence", "vence hoje", "vence amanha", "limite disponivel", "seu limite", "oferta", "emprestimo",
        "recusada", "negada", "nao aprovada", "nao autorizada", "cancelada", "agendad", "lembrete", "boleto disponivel",
        "investimento rendeu", "rendeu",
    };
    private static final String[] INCOME = {
        "recebeu", "recebido", "recebida", "credito em conta", "creditado", "deposito", "estorno", "estornad",
        "reembolso", "cashback", "caiu na sua conta", "salario",
    };
    private static final String[] EXPENSE = {
        "compra", "pagamento", "pagou", "enviad", "debito", "debitado", "saque", "transferiu", "voce gastou",
    };

    /** Pedaços que terminam o nome do estabelecimento/pessoa. */
    private static final String STOP = "(?=\\s+(?:para o|para seu|no cartao|no seu cartao|com o cartao|com cartao|cartao final|em \\d{2}/|"
        + "as \\d{1,2}[:h]|dia \\d|na conta|no credito|no debito|via pix|pelo pix)|[,.;:!\\n]|\\s+-\\s|$)";

    private BankParser() {
    }

    /** @param postedDate data em que a notificação chegou (AAAA-MM-DD), usada quando o texto não traz data. */
    public static Parsed parse(String title, String text, String postedDate) {
        String raw = ((title == null ? "" : title) + "\n" + (text == null ? "" : text)).trim();
        if (raw.isEmpty()) {
            return null;
        }
        String folded = fold(raw);
        for (String w : IGNORE) {
            if (folded.contains(w)) {
                return null;
            }
        }
        Matcher m = AMOUNT.matcher(raw);
        if (!m.find()) {
            return null;
        }
        long amount = cents(m.group(1));
        if (amount == 0) {
            return null;
        }
        String afterAmountFolded = fold(raw.substring(m.end()));

        boolean income = containsAny(folded, INCOME);
        boolean expense = containsAny(folded, EXPENSE);
        String type;
        if (income && !expense) {
            type = "INCOME";
        } else if (expense && !income) {
            type = "EXPENSE";
        } else if (income) {
            // "Você recebeu o pagamento de..." / "estorno da compra": o que diz o verbo principal vence.
            type = folded.matches("(?s).*\\b(recebeu|recebido|recebida|estorno|estornad|reembolso).*") ? "INCOME" : "EXPENSE";
        } else if (amount < 0) {
            type = "EXPENSE";
        } else {
            return null;
        }

        Kind kind;
        if (folded.contains("pix")) {
            kind = Kind.PIX;
        } else if (folded.contains("boleto")) {
            kind = Kind.BOLETO;
        } else if (folded.contains("cartao") || folded.contains("credito") && folded.contains("compra")
                || folded.contains("compra aprovada") || folded.contains("compra de r$") || folded.contains("compra no")) {
            kind = Kind.CARD;
        } else if (folded.contains("transfer") || folded.matches("(?s).*\\b(ted|doc)\\b.*")) {
            kind = Kind.TRANSFER;
        } else {
            kind = Kind.OTHER;
        }

        String last4 = null;
        Matcher lm = LAST4.matcher(folded);
        if (lm.find()) {
            last4 = lm.group(1);
        }

        String counterpart = counterpart(raw, m.end(), type, kind);
        String date = date(raw, postedDate);
        return new Parsed(type, Math.abs(amount), kind, counterpart, last4, date);
    }

    /** Estabelecimento (compras: "em X") ou pessoa (Pix/transferência: "de X" recebido, "para X" enviado). */
    private static String counterpart(String raw, int amountEnd, String type, Kind kind) {
        String after = raw.substring(amountEnd);
        String[] preps = "INCOME".equals(type) ? new String[] {"de", "do", "da"} : kind == Kind.CARD
            ? new String[] {"em", "no estabelecimento", "na loja"} : new String[] {"para", "a", "em", "ao", "à"};
        String found = firstAfter(after, preps);
        if (found == null) {
            // Ex.: "PADARIA X: compra de R$ 10,00" ou "Compra em PADARIA X de R$ 10,00".
            found = firstAfter(raw, preps);
        }
        if (found == null) {
            return null;
        }
        found = found.replaceAll("\\s+", " ").trim();
        String f = fold(found);
        if (found.length() < 2 || f.startsWith("r$") || f.matches("\\d.*") || f.startsWith("sua conta")
                || f.startsWith("seu cartao") || f.startsWith("cartao") || f.startsWith("conta") || f.equals("voce")) {
            return null;
        }
        return found.length() > 60 ? found.substring(0, 60).trim() : found;
    }

    private static String firstAfter(String s, String[] preps) {
        int best = -1;
        String bestValue = null;
        for (String p : preps) {
            Matcher m = Pattern.compile("(?iu)(?:^|\\s)" + Pattern.quote(p) + "\\s+(.+?)" + STOP).matcher(fold(s));
            if (m.find() && (best < 0 || m.start() < best)) {
                best = m.start();
                // Recorta do texto original (com acentos e maiúsculas), mesmas posições: fold preserva o tamanho.
                bestValue = s.substring(m.start(1), m.end(1));
            }
        }
        return bestValue;
    }

    private static String date(String raw, String posted) {
        Matcher m = DATE.matcher(raw);
        LocalDate base = posted != null ? LocalDate.parse(posted) : LocalDate.now();
        while (m.find()) {
            int d = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            if (d < 1 || d > 31 || mo < 1 || mo > 12) {
                continue;
            }
            int y = base.getYear();
            if (m.group(3) != null) {
                y = Integer.parseInt(m.group(3));
                if (y < 100) {
                    y += 2000;
                }
            }
            try {
                LocalDate dt = LocalDate.of(y, mo, Math.min(d, java.time.YearMonth.of(y, mo).lengthOfMonth()));
                if (m.group(3) == null && dt.isAfter(base.plusDays(1))) {
                    dt = dt.minusYears(1);
                }
                // Data muito distante da notificação é outra coisa (vencimento, validade): fica a do aviso.
                if (Math.abs(java.time.temporal.ChronoUnit.DAYS.between(dt, base)) <= 10) {
                    return dt.toString();
                }
            } catch (java.time.DateTimeException ignored) {
                // data inválida no texto
            }
        }
        return base.toString();
    }

    static long cents(String s) {
        boolean neg = s.startsWith("-");
        String t = s.replace("-", "").replace(".", "");
        String[] parts = t.split(",");
        long reais = Long.parseLong(parts[0]);
        long c = 0;
        if (parts.length > 1) {
            String frac = parts[1].length() == 1 ? parts[1] + "0" : parts[1];
            c = Long.parseLong(frac);
        }
        long v = reais * 100 + c;
        return neg ? -v : v;
    }

    /** Minúsculas sem acento, preservando o tamanho do texto (para recortar o original nas mesmas posições). */
    static String fold(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            String n = Normalizer.normalize(String.valueOf(ch), Normalizer.Form.NFD);
            char base = n.isEmpty() ? ch : n.charAt(0);
            out.append(Character.toLowerCase(base));
        }
        return out.toString();
    }

    private static boolean containsAny(String s, String[] words) {
        for (String w : words) {
            if (s.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** "PADARIA PAO QUENTE" → "Padaria Pao Quente"; mantém o que já tem minúsculas. */
    static String titleCase(String s) {
        if (!s.equals(s.toUpperCase(Locale.ROOT))) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        for (String w : s.toLowerCase(new Locale("pt", "BR")).split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            boolean small = w.length() <= 2 && (w.equals("de") || w.equals("da") || w.equals("do") || w.equals("e"));
            out.append(small && out.length() > 0 ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return out.toString();
    }
}
