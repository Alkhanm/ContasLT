package br.com.contaslt.services;

import static br.com.contaslt.services.CatalogServiceTest.assert422;
import static br.com.contaslt.services.Testing.account;
import static br.com.contaslt.services.Testing.card;
import static br.com.contaslt.services.Testing.tx;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.CardInvoice;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.dto.TransactionFilter;
import br.com.contaslt.dto.TransactionInput;
import br.com.contaslt.dto.TransactionPage;

/** Porte de frontend/src/services/cards.test.ts (CardInvoiceTest sem o importador). */
public class CardsServiceTest {

    private String checkingId;
    private String cardId;

    @Before
    public void setUp() {
        Testing.freshStore();
        checkingId = CatalogService.createAccount(account("Conta principal", "CHECKING", 0, "#1B5E3F")).id;
        // Fecha dia 25, vence dia 5 do mês seguinte.
        Account c = CatalogService.createAccount(card("Nubank", 0, "#820AD1", 25, 5));
        assertEquals(Integer.valueOf(25), c.closingDay);
        cardId = c.id;
    }

    private String transaction(String accountId, String description, double amount, String type, String dueDate) {
        return TransactionsService.createTransaction(tx(description, amount, type, dueDate, true, accountId, null)).transaction.id;
    }

    private long cardBalance() {
        for (Account a : CatalogService.listAccounts()) {
            if (a.id.equals(cardId)) {
                return a.balance;
            }
        }
        throw new AssertionError("cartão sumiu");
    }

    private CardInvoice invoice(String month) {
        return CardsService.summarizeInvoice(CardsService.requireCard(cardId), month);
    }

    private static TransactionPage list(String month, String accountId, Boolean invoice) {
        TransactionFilter f = new TransactionFilter();
        f.month = month;
        f.accountId = accountId;
        f.invoice = invoice;
        return TransactionsService.listTransactions(f);
    }

    private TransactionInput invoicePayment(String description, double amount, String dueDate, String invoiceMonth) {
        TransactionInput req = tx(description, amount, "EXPENSE", dueDate, true, checkingId, null);
        req.invoiceCardId = cardId;
        req.invoiceMonth = invoiceMonth;
        return req;
    }

    @Test
    public void comprasFicamForaDaListaGeralESeAgrupamPeloPeriodo() {
        transaction(cardId, "Mercado 25/09", 100, "EXPENSE", "2034-09-25"); // fecha em 25/09 → vence 05/10
        transaction(cardId, "Farmácia 26/09", 50, "EXPENSE", "2034-09-26"); // já é da fatura de novembro
        transaction(cardId, "Posto 10/09", 200, "EXPENSE", "2034-09-10");
        transaction(checkingId, "Padaria", 20, "EXPENSE", "2034-09-10");

        TransactionPage all = list("2034-09", null, null);
        assertEquals(1, all.items.size());
        assertEquals(2000, all.summary.expense);

        TransactionPage page = list("2034-10", cardId, true);
        assertEquals(2, page.items.size());
        CardInvoice inv = page.invoice;
        assertEquals("2034-10", inv.month);
        assertEquals("2034-08-26", inv.start);
        assertEquals("2034-09-25", inv.end);
        assertEquals("2034-10-05", inv.dueDate);
        assertEquals(30000, inv.purchases);
        assertEquals(2, inv.count);
        assertEquals(0, inv.paid);
        assertEquals("OPEN", inv.status); // datas no futuro: ainda não fechou

        TransactionPage plain = list("2034-09", cardId, null);
        assertEquals(3, plain.items.size());
        assertNull(plain.invoice);

        // Saldo do cartão = dívida em aberto (nenhuma fatura paga ainda).
        assertEquals(-35000, cardBalance());
    }

    @Test
    public void pagamentoDaFaturaEADespesaEAgregaAsCompras() {
        transaction(cardId, "Mercado", 300, "EXPENSE", "2034-09-10");
        transaction(cardId, "Farmácia nov", 50, "EXPENSE", "2034-09-26"); // fatura de novembro, segue em aberto

        TransactionInput req = invoicePayment("Fatura Nubank", 500, "2034-10-05", null);
        Transaction dto = TransactionsService.createTransaction(req).transaction;
        assertEquals(cardId, dto.invoiceCardId);
        assertEquals("2034-10", dto.invoiceMonth); // inferido pelo vencimento mais próximo
        assertEquals(30000, dto.invoice.purchases);
        assertEquals(1, dto.invoice.count);
        assertEquals(20000, dto.invoice.undetailed);
        assertEquals("PAID", dto.invoice.status);

        TransactionPage oct = list("2034-10", null, null);
        assertEquals(1, oct.items.size());
        assertEquals(1, oct.items.get(0).invoice.count);
        assertEquals(50000, oct.summary.expense);

        assertEquals(-5000, cardBalance());
        CardInvoice inv = invoice("2034-10");
        assertEquals(50000, inv.paid);
        assertEquals(dto.id, inv.paymentTransactionId);

        // Comprovante lido depois entra na fatura e reduz o "não detalhado".
        transaction(cardId, "Restaurante", 120, "EXPENSE", "2034-09-20");
        inv = invoice("2034-10");
        assertEquals(42000, inv.purchases);
        assertEquals(8000, inv.undetailed);
        assertEquals(-5000, cardBalance());

        // Pagar a fatura a partir do próprio cartão não faz sentido.
        req.accountId = cardId;
        assert422(() -> TransactionsService.createTransaction(req));
    }

    @Test
    public void pagamentoInformadoComOMesDaFaturaEstornoAbateACompra() {
        transaction(cardId, "Mercado", 300, "EXPENSE", "2034-09-10");
        transaction(checkingId, "PADARIA", 35.9, "EXPENSE", "2034-10-06");
        TransactionsService.createTransaction(invoicePayment("PAGAMENTO FATURA NUBANK", 480, "2034-10-05", "2034-10"));
        assertEquals(51590, list("2034-10", checkingId, null).summary.expense);
        CardInvoice inv = invoice("2034-10");
        assertEquals(48000, inv.paid);
        assertEquals(18000, inv.undetailed);
        assertEquals("PAID", inv.status);
        assertEquals(0, cardBalance());

        // Estorno (receita no cartão) na fatura de novembro: abate a dívida em aberto.
        transaction(cardId, "Compra nov", 100, "EXPENSE", "2034-10-10");
        transaction(cardId, "Estorno", 30, "INCOME", "2034-10-12");
        assertEquals(-7000, cardBalance());
        inv = invoice("2034-11");
        assertEquals(7000, inv.purchases);
        assertEquals(2, inv.count);
        assertEquals(7000, inv.remaining);
        assertEquals("OPEN", inv.status);

        // Pagamento parcial.
        TransactionsService.createTransaction(invoicePayment("Parte da fatura", 50, "2034-11-05", null));
        inv = invoice("2034-11");
        assertEquals("PARTIAL", inv.status);
        assertEquals(2000, inv.remaining);
        assertEquals(0, cardBalance());
    }

    @Test
    public void validacoesDoCartaoEDoPagamentoDeFatura() {
        assert422(() -> CatalogService.createAccount(card("X", 0, "#820AD1", 25, null)));
        assert422(() -> CatalogService.createAccount(card("X", 0, "#820AD1", 5, 5)));
        // Conta comum perde fechamento/vencimento.
        var y = account("Y", "CHECKING", 0, "#820AD1");
        y.closingDay = 5;
        y.dueDay = 10;
        assertNull(CatalogService.createAccount(y).closingDay);
        // Pagamento de fatura precisa ser despesa e o cartão precisa ser cartão.
        TransactionInput base = tx("Fatura", 10, "INCOME", "2034-10-05", true, checkingId, null);
        base.invoiceCardId = cardId;
        assert422(() -> TransactionsService.createTransaction(base));
        TransactionInput notCard = tx("Fatura", 10, "EXPENSE", "2034-10-05", true, checkingId, null);
        notCard.invoiceCardId = checkingId;
        assert422(() -> TransactionsService.createTransaction(notCard));
    }

    @Test
    public void faturaFechadaQuandoHojePassouDoFechamento() {
        setToday("2034-10-01");
        transaction(cardId, "Mercado", 100, "EXPENSE", "2034-09-10");
        assertEquals("CLOSED", invoice("2034-10").status);
    }

    private static void setToday(String date) {
        br.com.contaslt.domain.Dates.setTodayForTests(date);
    }
}
