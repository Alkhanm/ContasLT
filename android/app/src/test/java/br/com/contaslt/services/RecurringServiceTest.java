package br.com.contaslt.services;

import static br.com.contaslt.services.Testing.account;
import static br.com.contaslt.services.Testing.cents;
import static br.com.contaslt.services.Testing.tx;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.data.schema.TransactionRec;
import br.com.contaslt.domain.Matcher;
import br.com.contaslt.dto.RecurringBill;
import br.com.contaslt.dto.RecurringBillInput;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.dto.TransactionFilter;
import br.com.contaslt.dto.TransactionInput;

/**
 * Porte de frontend/src/services/recurring.test.ts: RecurringEditScopeTest, RecurringFromTransactionTest e as partes
 * de RecurringMatchTest que não dependem do importador.
 */
public class RecurringServiceTest {

    private String accountId;

    @Before
    public void setUp() {
        Testing.freshStore();
        accountId = CatalogService.createAccount(account("Conta principal", "CHECKING", 0, "#1B5E3F")).id;
    }

    private String bill(String description, double amount, String startDate, Integer occurrences, Integer matchStrictness) {
        RecurringBillInput req = Testing.bill(description, amount, "EXPENSE", "MONTHLY", startDate, occurrences, accountId, null);
        req.matchStrictness = matchStrictness;
        return RecurringService.createRecurring(req).id;
    }

    private String bill(String description, double amount, String startDate) {
        return bill(description, amount, startDate, null, null);
    }

    private TransactionsService.SaveResult edit(String id, String description, double amount, String dueDate, String scope) {
        return TransactionsService.updateTransaction(id, tx(description, amount, "EXPENSE", dueDate, false, accountId, null), scope);
    }

    private static List<Transaction> items(String month) {
        TransactionFilter f = new TransactionFilter();
        f.month = month;
        return TransactionsService.listTransactions(f).items;
    }

    private String transaction(String description, double amount, String dueDate) {
        return TransactionsService.createTransaction(tx(description, amount, "EXPENSE", dueDate, true, accountId, null)).transaction.id;
    }

    // ---------- escopo de edição de uma ocorrência ----------

    @Test
    public void moverUmaOcorrenciaNaoRecriaADataOriginal() {
        bill("Internet", 100, "2033-03-20");
        List<Transaction> april = items("2033-04");
        assertEquals(1, april.size());
        String id = april.get(0).id;

        edit(id, "Internet", 100, "2033-04-01", "THIS");
        april = items("2033-04");
        assertEquals(1, april.size());
        assertEquals("2033-04-01", april.get(0).dueDate);
        edit(id, "Internet", 100, "2033-04-02", "THIS");
        assertEquals(1, items("2033-04").size());

        assertEquals("2033-05-20", items("2033-05").get(0).dueDate);
    }

    @Test
    public void valorNovoValeParaEstaEAsProximas() {
        String billId = bill("Academia", 100, "2033-03-20");
        items("2033-03");
        items("2033-04");
        String mayId = items("2033-05").get(0).id;

        edit(mayId, "Academia Plus", 150, "2033-05-20", "FOLLOWING");

        assertEquals(10000, items("2033-04").get(0).amount);
        assertEquals(15000, items("2033-05").get(0).amount);
        Transaction june = items("2033-06").get(0);
        assertEquals(15000, june.amount);
        assertEquals("Academia Plus", june.description);
        assertEquals("2033-06-20", june.dueDate);

        RecurringBill b = null;
        for (RecurringBill r : RecurringService.listRecurring()) {
            if (r.id.equals(billId)) {
                b = r;
            }
        }
        assertNotNull(b);
        assertEquals(15000, b.amount);
        assertEquals("Academia Plus", b.description);
    }

    @Test
    public void dataNovaReancoraOsProximosVencimentos() {
        bill("Aluguel", 1500, "2033-03-20");
        items("2033-05");
        String juneId = items("2033-06").get(0).id;
        items("2033-07");

        edit(juneId, "Aluguel", 1500, "2033-06-01", "FOLLOWING");

        assertEquals("2033-05-20", items("2033-05").get(0).dueDate);
        List<Transaction> june = items("2033-06");
        assertEquals(1, june.size());
        assertEquals(juneId, june.get(0).id);
        assertEquals("2033-06-01", june.get(0).dueDate);
        List<Transaction> july = items("2033-07");
        assertEquals(1, july.size());
        assertEquals("2033-07-01", july.get(0).dueDate);
        assertEquals("2033-08-01", items("2033-08").get(0).dueDate);
    }

    @Test
    public void reancorarParcelamentoMantemNumeracaoETotal() {
        bill("Geladeira", 300, "2034-01-10", 6, null);
        items("2034-01");
        items("2034-02");
        Transaction third = items("2034-03").get(0);
        assertEquals(Integer.valueOf(3), third.installmentNumber);

        edit(third.id, "Geladeira", 300, "2034-03-01", "FOLLOWING");

        Transaction april = items("2034-04").get(0);
        assertEquals("2034-04-01", april.dueDate);
        assertEquals(Integer.valueOf(4), april.installmentNumber);
        assertEquals(Integer.valueOf(6), april.installmentTotal);
        assertEquals(Integer.valueOf(6), items("2034-06").get(0).installmentNumber);
        assertEquals(0, items("2034-07").size());
        assertEquals("2034-02-10", items("2034-02").get(0).dueDate);
    }

    // ---------- lançamento existente vira conta recorrente ----------

    @Test
    public void lancamentoImportadoViraAPrimeiraOcorrencia() {
        String txId = TransactionsService.createTransaction(
            tx("PAGTO ENEL DISTRIBUICAO", 189.9, "EXPENSE", "2031-05-10", true, accountId, null)).transaction.id;
        RecurringBillInput req = Testing.bill("Conta de luz Enel", 189.9, "EXPENSE", "MONTHLY", "2031-05-10", null, accountId, null);
        req.fromTransactionId = txId;
        String billId = RecurringService.createRecurring(req).id;

        List<Transaction> may = items("2031-05");
        assertEquals(1, may.size());
        assertEquals(txId, may.get(0).id);
        assertEquals(billId, may.get(0).recurringBillId);
        assertEquals("PAGTO ENEL DISTRIBUICAO", may.get(0).description);
        assertTrue(may.get(0).paid);

        List<Transaction> june = items("2031-06");
        assertEquals(1, june.size());
        assertEquals(billId, june.get(0).recurringBillId);
        assertEquals("2031-06-10", june.get(0).dueDate);
        assertFalse(june.get(0).paid);

        req.description = "Outra";
        CatalogServiceTest.assert422(() -> RecurringService.createRecurring(req));
    }

    // ---------- extrato e comprovante casam com vencimentos pendentes ----------

    /** Linha lida: reais com sinal (negativo = despesa). */
    static final class Row {
        final String date;
        final double amount;
        final String description;

        Row(String date, double amount, String description) {
            this.date = date;
            this.amount = amount;
            this.description = description;
        }
    }

    private static Matcher.Candidate toCandidate(Row r) {
        return new Matcher.Candidate(r.description, Math.round(Math.abs(r.amount) * 100), r.amount < 0 ? "EXPENSE" : "INCOME",
            r.date);
    }

    private static Map<Row, RecurringService.ScoredOccurrence> preview(Row... rows) {
        List<String> dates = new ArrayList<>();
        for (Row r : rows) {
            dates.add(r.date);
        }
        Collections.sort(dates);
        return RecurringService.matchPending(Arrays.asList(rows), RecurringServiceTest::toCandidate, dates.get(0),
            dates.get(dates.size() - 1));
    }

    @Test
    public void linhaDoExtratoQuitaOVencimento() {
        String billId = bill("Internet LINQ", 99.99, "2035-03-01");
        Row linq = new Row("2035-03-10", -102, "Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA");
        Row market = new Row("2035-03-10", -102, "SUPERMERCADO ALMEIDA");
        Map<Row, RecurringService.ScoredOccurrence> pv = preview(linq, market);
        RecurringService.ScoredOccurrence m = pv.get(linq);
        assertNotNull(m);
        assertEquals("Internet LINQ", m.occurrence.description);
        assertEquals("2035-03-01", m.occurrence.dueDate);
        assertEquals(9999, m.occurrence.amount);
        assertFalse(pv.containsKey(market));

        TransactionRec settled = RecurringService.settle(m.occurrence, 10200, linq.date);
        settled.externalId = "A1";
        Ctx.db().put(Entity.TRANSACTION, settled);
        TransactionsService.createTransaction(tx(market.description, 102, "EXPENSE", market.date, true, accountId, null));

        Transaction occ = TransactionsService.getTransaction(m.occurrence.id);
        assertTrue(occ.paid);
        assertEquals(10200, occ.amount);
        assertEquals("2035-03-10", occ.dueDate);
        assertEquals("2035-03-01", occ.scheduledDate);
        assertEquals(Long.valueOf(9999), occ.expectedAmount);
        assertEquals(billId, occ.recurringBillId);
        assertEquals("Internet LINQ", occ.description);

        assertEquals(2, items("2035-03").size());
        List<Transaction> april = items("2035-04");
        assertEquals(1, april.size());
        assertEquals(9999, april.get(0).amount);
    }

    @Test
    public void precisaoControlaQuantoDataEValorPodemVariar() {
        bill("Energia Enel", 200, "2035-05-05", null, 10);
        bill("Água Saneago", 80, "2035-05-05", null, 1);
        Row enel = new Row("2035-05-12", -204, "PAGTO ENEL DISTRIBUICAO");
        Row saneago = new Row("2035-05-28", -115, "SANEAGO SANEAMENTO DE GOIAS");
        Map<Row, RecurringService.ScoredOccurrence> pv = preview(enel, saneago);
        assertFalse(pv.containsKey(enel));
        assertEquals("Água Saneago", pv.get(saneago).occurrence.description);

        Row exact = new Row("2035-05-05", -200, "PAGTO ENEL DISTRIBUICAO");
        assertEquals("Energia Enel", preview(exact).get(exact).occurrence.description);
    }

    @Test
    public void pagamentosAnterioresEnsinamComoAContaApareceNoExtrato() {
        String txId = transaction("Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA", 99.99, "2035-07-20");
        RecurringBillInput req = Testing.bill("Internet", 99.99, "EXPENSE", "MONTHLY", "2035-07-20", null, accountId, null);
        req.fromTransactionId = txId;
        RecurringService.createRecurring(req);
        Row row = new Row("2035-08-22", -99.99, "Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA");
        RecurringService.ScoredOccurrence m = preview(row).get(row);
        assertEquals("Internet", m.occurrence.description);
        assertEquals("2035-08-20", m.occurrence.dueDate);
    }

    @Test
    public void comprovanteSugereOVencimentoPendente() {
        String billId = bill("Internet LINQ", 99.99, "2035-09-01");
        Row scan = new Row("2035-09-09", -101.5, "LINQ Telecomunicacoes");
        RecurringService.ScoredOccurrence m = preview(scan).get(scan);
        assertEquals("Internet LINQ", m.occurrence.description);
        assertEquals("2035-09-01", m.occurrence.dueDate);
        assertEquals(9999, m.occurrence.amount);
        assertEquals(accountId, m.occurrence.accountId);

        TransactionInput req = tx("Internet LINQ", 101.5, "EXPENSE", "2035-09-09", true, accountId, null);
        req.merchant = "LINQ Telecomunicacoes";
        TransactionsService.updateTransaction(m.occurrence.id, req);
        List<Transaction> sept = items("2035-09");
        assertEquals(1, sept.size());
        assertEquals(billId, sept.get(0).recurringBillId);
        assertTrue(sept.get(0).paid);
        assertEquals("2035-09-01", sept.get(0).scheduledDate);
        assertEquals(cents(101.5), TransactionsService.requireTransaction(m.occurrence.id).amount);
    }

    @Test
    public void linhasIguaisSaoCandidatosDiferentes() {
        bill("Internet LINQ", 99.99, "2035-03-01");
        bill("Internet LINQ", 99.99, "2035-03-02");
        Row a = new Row("2035-03-01", -99.99, "LINQ");
        Row b = new Row("2035-03-01", -99.99, "LINQ");
        Map<Row, RecurringService.ScoredOccurrence> pv = preview(a, b);
        assertEquals(2, pv.size());
        assertTrue(pv.get(a).occurrence.id != null && !pv.get(a).occurrence.id.equals(pv.get(b).occurrence.id));
        assertNull(pv.get(new Row("2035-03-01", -99.99, "LINQ")));
    }
}
