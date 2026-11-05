package br.com.contaslt.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.data.schema.TransactionRec;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.dto.TransactionFilter;
import br.com.contaslt.services.CatalogService;
import br.com.contaslt.services.Ctx;
import br.com.contaslt.services.RecurringService;
import br.com.contaslt.services.Testing;
import br.com.contaslt.services.TransactionsService;

/** Sugestões da caixa "A confirmar" usando as regras locais (conta, categoria, recorrente, duplicata). */
public class InboxServiceTest {

    private Testing.Catalog cat;
    private Account card;

    @Before
    public void setUp() {
        Testing.freshStore();
        Dates.setTodayForTests("2026-11-05");
        cat = Testing.basicCatalog();
        card = CatalogService.createAccount(Testing.card("Nubank final 1234", 0, "#7c3aed", 25, 5));
        CatalogService.saveRule("padaria", cat.expense.id);
    }

    @After
    public void tearDown() {
        Dates.resetForTests();
    }

    private static InboxItem item(String title, String text) {
        BankParser.Parsed p = BankParser.parse(title, text, "2026-11-05");
        InboxItem i = new InboxItem();
        i.id = "i1";
        i.packageName = "com.nu.production";
        i.appLabel = "Nubank";
        i.title = title;
        i.text = text;
        i.status = InboxItem.PENDING;
        i.type = p.type;
        i.amount = p.amount;
        i.kind = p.kind.name();
        i.counterpart = p.counterpart;
        i.cardLast4 = p.cardLast4;
        i.date = p.date;
        i.description = p.description();
        return i;
    }

    @Test
    public void compraNoCreditoVaiParaOCartaoPeloFinalComCategoriaDaRegra() {
        InboxService.Suggestion s = InboxService.suggest(
            item("Compra aprovada", "Compra de R$ 45,90 APROVADA em PADARIA PAO QUENTE para o cartão com final 1234."), null, null);
        assertEquals(card.id, s.input.accountId);
        assertEquals(cat.expense.id, s.input.categoryId);
        assertEquals(Long.valueOf(4590), s.input.amount);
        assertEquals("Padaria Pao Quente", s.input.description);
        assertTrue(s.input.paid);

        Transaction t = InboxService.confirm(s);
        assertEquals("Padaria Pao Quente", t.description);
        assertEquals("PADARIA PAO QUENTE", t.merchant);
        assertEquals(card.id, t.accountId);

        // A mesma compra de novo aparece como possível duplicata.
        InboxService.Suggestion again = InboxService.suggest(
            item("Compra aprovada", "Compra de R$ 45,90 APROVADA em PADARIA PAO QUENTE para o cartão com final 1234."), null, null);
        assertEquals(t.id, again.duplicateOfId);
    }

    @Test
    public void pixRecebidoVaiParaAContaPadraoDoApp() {
        Account pj = CatalogService.createAccount(Testing.account("PJ", "CHECKING", 0, "#000000"));
        InboxService.Suggestion s = InboxService.suggest(item("Pix recebido", "Você recebeu um Pix de R$ 200,00 de Maria da Silva."), pj.id, null);
        assertEquals(pj.id, s.input.accountId);
        assertEquals("INCOME", s.input.type);
        assertNull(s.input.categoryId);
        // Sem conta padrão: a primeira conta ativa de dinheiro.
        InboxService.Suggestion s2 = InboxService.suggest(item("Pix recebido", "Você recebeu um Pix de R$ 200,00 de Maria da Silva."), null, null);
        assertNotNull(s2.input.accountId);
    }

    @Test
    public void pagamentoDeRecorrentePagaOVencimento() {
        RecurringService.createRecurring(Testing.bill("Netflix", 55.90, "EXPENSE", "MONTHLY", "2026-10-04", null, cat.account.id, cat.expense.id));
        TransactionsService.listTransactions(filter("2026-11"));
        InboxService.Suggestion s = InboxService.suggest(item("Compra aprovada", "Compra de R$ 55,90 APROVADA em NETFLIX.COM no débito"), null, null);
        assertNotNull(s.occurrenceId);
        assertEquals("Netflix", s.occurrenceDescription);

        Transaction t = InboxService.confirm(s);
        TransactionRec occ = Ctx.db().get(Entity.TRANSACTION, s.occurrenceId);
        assertTrue(occ.paid);
        assertEquals(5590, occ.amount);
        assertEquals("2026-11-05", occ.dueDate);
        assertEquals(s.occurrenceId, t.id);
    }

    private static TransactionFilter filter(String month) {
        TransactionFilter f = new TransactionFilter();
        f.month = month;
        return f;
    }
}
