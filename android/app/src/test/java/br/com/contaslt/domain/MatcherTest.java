package br.com.contaslt.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import br.com.contaslt.data.schema.RecurringBillRec;
import br.com.contaslt.data.schema.TransactionRec;

/** Porte de frontend/src/domain/matcher.test.ts. */
public class MatcherTest {

    private static RecurringBillRec bill(int matchStrictness) {
        RecurringBillRec b = new RecurringBillRec();
        b.type = "EXPENSE";
        b.matchStrictness = matchStrictness;
        return b;
    }

    private static TransactionRec occ(long amount, String dueDate) {
        TransactionRec t = new TransactionRec();
        t.amount = amount;
        t.dueDate = dueDate;
        return t;
    }

    private static Matcher.Candidate cand(String description, Long amount, String type, String date) {
        return new Matcher.Candidate(description, amount, type, date);
    }

    private static List<String> sorted(Set<String> s) {
        List<String> l = new ArrayList<>(s);
        Collections.sort(l);
        return l;
    }

    @Test
    public void palavrasSignificativas() {
        assertEquals(Arrays.asList("linq", "telecomunicacoes"),
            sorted(Matcher.tokens("Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA")));
        assertEquals(Arrays.asList("agua", "saneago"), sorted(Matcher.tokens("Água Saneago")));
        assertEquals(0, Matcher.tokens(null).size());
    }

    @Test
    public void pagamentosAnterioresEnsinam() {
        Set<String> keys = Matcher.keyTokens("Internet",
            Arrays.asList(" Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA"));
        assertEquals(Arrays.asList("internet", "linq", "telecomunicacoes"), sorted(keys));
        TransactionRec o = occ(9999, "2035-08-20");
        Matcher.Candidate c = cand("Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA", 9999L, "EXPENSE", "2035-08-22");
        assertEquals(0, Matcher.score(o, bill(5), Matcher.tokens("Internet"), c));
        assertTrue(Matcher.score(o, bill(5), keys, c) > 0);
    }

    @Test
    public void boletoComMultaCasaPeloNome() {
        TransactionRec o = occ(9999, "2035-03-01");
        Set<String> keys = Matcher.keyTokens("Internet LINQ", Collections.emptyList());
        String desc = "Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA";
        Matcher.Candidate linq = cand(desc, 10200L, "EXPENSE", "2035-03-10");
        Matcher.Candidate market = cand("SUPERMERCADO ALMEIDA", 10200L, "EXPENSE", "2035-03-10");
        assertEquals(30 + 54 + 32, Matcher.score(o, bill(5), keys, linq));
        assertEquals(0, Matcher.score(o, bill(5), keys, market));
        assertEquals(0, Matcher.score(o, bill(5), keys, cand(desc, 10200L, "INCOME", "2035-03-10")));
        assertEquals(0, Matcher.score(o, bill(5), keys, cand(desc, null, "EXPENSE", "2035-03-10")));
        assertEquals(0, Matcher.score(o, bill(5), keys, cand(desc, 10200L, "EXPENSE", null)));
        assertEquals(0, Matcher.score(o, null, keys, linq));
    }

    @Test
    public void precisaoControlaVariacao() {
        TransactionRec enel = occ(20000, "2035-05-05");
        Set<String> enelKeys = Matcher.keyTokens("Energia Enel", Collections.emptyList());
        assertEquals(0, Matcher.score(enel, bill(10), enelKeys, cand("PAGTO ENEL DISTRIBUICAO", 20400L, "EXPENSE", "2035-05-12")));
        assertTrue(Matcher.score(enel, bill(10), enelKeys, cand("PAGTO ENEL DISTRIBUICAO", 20000L, "EXPENSE", "2035-05-05")) > 0);
        TransactionRec agua = occ(8000, "2035-05-05");
        Set<String> aguaKeys = Matcher.keyTokens("Água Saneago", Collections.emptyList());
        assertTrue(Matcher.score(agua, bill(1), aguaKeys, cand("SANEAGO SANEAMENTO DE GOIAS", 11500L, "EXPENSE", "2035-05-28")) > 0);
        assertEquals(0, Matcher.score(agua, bill(1), aguaKeys, cand("SANEAGO", 8000L, "EXPENSE", "2035-06-20")));
    }

    @Test
    public void toleranciasEDiferencaPercentual() {
        assertEquals(new Matcher.Tolerance(-1, 45), Matcher.toleranceFor(0));
        assertEquals(new Matcher.Tolerance(30, 15), Matcher.toleranceFor(5));
        assertEquals(new Matcher.Tolerance(0, 0), Matcher.toleranceFor(99));
        assertEquals(3, Matcher.amountDiffPercent(9999L, 10200L));
        assertEquals(2, Matcher.amountDiffPercent(20000L, 20400L));
        assertEquals(0, Matcher.amountDiffPercent(20000L, 20000L));
        assertEquals(0, Matcher.amountDiffPercent(0L, 0L));
        assertEquals(100, Matcher.amountDiffPercent(0L, 100L));
        assertEquals(0, Matcher.amountDiffPercent(null, null));
    }
}
