package br.com.contaslt.recurring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import br.com.contaslt.common.TextNormalizer;
import br.com.contaslt.common.TransactionType;
import br.com.contaslt.transaction.Transaction;

/**
 * Decide se uma linha de extrato (ou um comprovante) é o pagamento de um vencimento pendente de conta recorrente.
 * A chave forte é o nome: alguma palavra significativa em comum entre a descrição lida e a da regra (ou a dos
 * pagamentos anteriores dela). Data e valor podem variar dentro da tolerância dada pelo nível de precisão da regra,
 * de 1 (praticamente só o nome, no mesmo mês) a 10 (nome, valor exato e data exata).
 */
public final class OccurrenceMatcher {

    private OccurrenceMatcher() {
    }

    /** O que foi lido do extrato ou do comprovante. */
    public record Candidate(String description, BigDecimal amount, TransactionType type, LocalDate date) {
    }

    /** Tolerância de um nível: percentual máximo de diferença de valor (-1 = qualquer) e dias de distância da data prevista. */
    public record Tolerance(int amountPercent, int days) {
    }

    public static final int DEFAULT_LEVEL = 5;
    /** Maior janela de dias possível: usada para buscar as ocorrências candidatas no banco. */
    public static final int MAX_DAYS = 45;

    private static final Set<String> STOP_WORDS = Set.of(
        "pagamento", "pagto", "pgto", "pag", "boleto", "efetuado", "efetuada", "compra", "cartao", "debito", "credito",
        "pix", "enviado", "enviada", "recebido", "recebida", "transferencia", "transf", "ted", "doc", "ltda", "eireli",
        "epp", "com", "para", "pelo", "pela", "dos", "das", "por", "via", "ref", "referente", "fatura", "conta",
        "aut", "automatico", "automatica", "deb", "cred", "lancamento", "servicos", "servico", "mensalidade");

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

    /** Palavras significativas (3+ letras, fora termos bancários genéricos) de um texto. */
    public static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        for (String t : TextNormalizer.normalize(text).split(" ")) {
            if (t.length() >= 3 && !STOP_WORDS.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Palavras-chave da regra: nome dela mais descrições/estabelecimentos dos pagamentos anteriores. */
    public static Set<String> keyTokens(RecurringBill bill, Collection<String> history) {
        Set<String> keys = tokens(bill.getDescription());
        for (String h : history) {
            keys.addAll(tokens(h));
        }
        return keys;
    }

    /**
     * Pontuação do casamento entre um vencimento pendente e o candidato, ou 0 quando não casa.
     * Quanto maior, mais parecido: palavras em comum pesam mais, depois proximidade de valor e de data.
     */
    public static int score(Transaction occurrence, Set<String> keyTokens, Candidate c) {
        RecurringBill bill = occurrence.getRecurringBill();
        if (bill == null || c.type() != bill.getType() || c.amount() == null || c.date() == null) {
            return 0;
        }
        Set<String> candidateTokens = tokens(c.description());
        int hits = 0;
        for (String k : keyTokens) {
            if (candidateTokens.contains(k)) {
                hits++;
            }
        }
        if (hits == 0) {
            return 0;
        }
        Tolerance tol = toleranceFor(bill.getMatchStrictness());
        int diffPercent = amountDiffPercent(occurrence.getAmount(), c.amount());
        if (tol.amountPercent() >= 0 && diffPercent > tol.amountPercent()) {
            return 0;
        }
        int days = (int) Math.abs(ChronoUnit.DAYS.between(occurrence.getDueDate(), c.date()));
        if (days > tol.days()) {
            return 0;
        }
        return Math.min(hits, 3) * 30
            + Math.max(0, 60 - diffPercent * 2)
            + Math.max(0, 50 - days * 2);
    }

    /** Diferença percentual (inteira, arredondada para cima) entre o valor previsto e o lido. */
    static int amountDiffPercent(BigDecimal expected, BigDecimal actual) {
        if (expected == null || expected.signum() == 0) {
            return actual == null || actual.signum() == 0 ? 0 : 100;
        }
        BigDecimal diff = actual.subtract(expected).abs().multiply(BigDecimal.valueOf(100));
        return diff.divide(expected, 0, RoundingMode.CEILING).intValue();
    }
}
