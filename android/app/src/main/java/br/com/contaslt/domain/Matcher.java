package br.com.contaslt.domain;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import br.com.contaslt.data.schema.RecurringBillRec;
import br.com.contaslt.data.schema.TransactionRec;

/**
 * Decide se uma linha de extrato (ou um comprovante) é o pagamento de um vencimento pendente de conta recorrente
 * (porte de frontend/src/domain/matcher.ts). A chave forte é o nome: alguma palavra significativa em comum com a regra
 * ou com os pagamentos anteriores dela. Data e valor variam dentro da tolerância do nível de precisão da regra (1 a 10).
 */
public final class Matcher {

    /** Linha lida (extrato ou comprovante). {@code amount} em centavos. */
    public static final class Candidate {
        public final String description;
        public final Long amount;
        public final String type;
        public final String date;

        public Candidate(String description, Long amount, String type, String date) {
            this.description = description;
            this.amount = amount;
            this.type = type;
            this.date = date;
        }
    }

    /** amountPercent: diferença máxima de valor em %, ou -1 = qualquer. */
    public record Tolerance(int amountPercent, int days) {
    }

    public static final int DEFAULT_LEVEL = 5;
    /** Maior janela de dias possível. */
    public static final int MAX_DAYS = 45;

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
        "pagamento", "pagto", "pgto", "pag", "boleto", "efetuado", "efetuada", "compra", "cartao", "debito", "credito",
        "pix", "enviado", "enviada", "recebido", "recebida", "transferencia", "transf", "ted", "doc", "ltda", "eireli",
        "epp", "com", "para", "pelo", "pela", "dos", "das", "por", "via", "ref", "referente", "fatura", "conta",
        "aut", "automatico", "automatica", "deb", "cred", "lancamento", "servicos", "servico", "mensalidade"));

    private Matcher() {
    }

    public static Tolerance toleranceFor(int level) {
        return switch (Math.max(1, Math.min(10, level))) {
            case 1 -> new Tolerance(-1, MAX_DAYS);
            case 2 -> new Tolerance(75, 35);
            case 3 -> new Tolerance(50, 28);
            case 4 -> new Tolerance(40, 21);
            case 5 -> new Tolerance(30, 15);
            case 6 -> new Tolerance(20, 10);
            case 7 -> new Tolerance(12, 7);
            case 8 -> new Tolerance(6, 4);
            case 9 -> new Tolerance(2, 2);
            default -> new Tolerance(0, 0);
        };
    }

    /** Palavras significativas (3+ letras, fora termos bancários genéricos). */
    public static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : Text.normalize(text).split(" ", -1)) {
            if (t.length() >= 3 && !STOP_WORDS.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Palavras-chave da regra: nome dela mais descrições/estabelecimentos dos pagamentos anteriores. */
    public static Set<String> keyTokens(String billDescription, Iterable<String> history) {
        Set<String> keys = tokens(billDescription);
        for (String h : history) {
            keys.addAll(tokens(h));
        }
        return keys;
    }

    public static Set<String> keyTokens(RecurringBillRec bill, Iterable<String> history) {
        return keyTokens(bill.description, history);
    }

    /**
     * Pontuação do casamento entre um vencimento pendente (usa amount e dueDate) e o candidato; 0 = não casa. Da regra
     * usa type e matchStrictness.
     */
    public static int score(TransactionRec occurrence, RecurringBillRec bill, Set<String> keys, Candidate c) {
        if (bill == null || !c.type.equals(bill.type) || c.amount == null || c.date == null) {
            return 0;
        }
        Set<String> candidateTokens = tokens(c.description);
        int hits = 0;
        for (String k : keys) {
            if (candidateTokens.contains(k)) {
                hits++;
            }
        }
        if (hits == 0) {
            return 0;
        }
        Tolerance tol = toleranceFor(bill.matchStrictness);
        long diffPercent = amountDiffPercent(occurrence.amount, c.amount);
        if (tol.amountPercent() >= 0 && diffPercent > tol.amountPercent()) {
            return 0;
        }
        long days = Math.abs(Dates.daysBetween(occurrence.dueDate, c.date));
        if (days > tol.days()) {
            return 0;
        }
        return (int) (Math.min(hits, 3) * 30L + Math.max(0, 60 - diffPercent * 2) + Math.max(0, 50 - days * 2));
    }

    /** Diferença percentual (inteira, arredondada para cima) entre o previsto e o lido. */
    public static long amountDiffPercent(Long expected, Long actual) {
        if (expected == null || expected == 0) {
            return actual == null || actual == 0 ? 0 : 100;
        }
        long diff = Math.abs((actual == null ? 0 : actual) - expected) * 100;
        return (long) Math.ceil((double) diff / Math.abs(expected));
    }
}
