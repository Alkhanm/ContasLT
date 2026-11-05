package br.com.contaslt.services;

import static br.com.contaslt.services.CatalogServiceTest.assert422;
import static br.com.contaslt.services.Testing.account;
import static br.com.contaslt.services.Testing.tx;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import br.com.contaslt.data.ApiException;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.AccountInput;
import br.com.contaslt.dto.Category;
import br.com.contaslt.dto.Dashboard;
import br.com.contaslt.dto.MonthTotal;
import br.com.contaslt.dto.RecurringBillInput;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.dto.TransactionFilter;
import br.com.contaslt.dto.TransactionInput;
import br.com.contaslt.dto.TransactionPage;

/**
 * Porte de frontend/src/services/apiflow.test.ts (ApiFlowTest): os testes rodam em sequência sobre o mesmo store, na
 * ordem dos nomes. O "hoje" fica fixo para o resultado não depender do dia em que o teste roda.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ApiFlowTest {

    private static String accountId;
    private static String expenseCategoryId;
    private static String incomeCategoryId;
    private static String billId;

    @BeforeClass
    public static void setUp() {
        Testing.freshStore();
        Dates.setTodayForTests("2026-11-05");
    }

    @AfterClass
    public static void tearDown() {
        Dates.resetForTests();
    }

    private static TransactionPage list(String month) {
        TransactionFilter f = new TransactionFilter();
        f.month = month;
        return TransactionsService.listTransactions(f);
    }

    @Test
    public void t1_cadastroCriaCategoriasPadraoEContaPrincipal() {
        CatalogService.seedDefaults();
        List<Account> accounts = CatalogService.listAccounts();
        assertEquals(1, accounts.size());
        assertEquals("Conta principal", accounts.get(0).name);
        accountId = accounts.get(0).id;

        List<Category> categories = CatalogService.listCategories();
        assertTrue(categories.size() > 10);
        for (Category c : categories) {
            if (expenseCategoryId == null && c.type.equals("EXPENSE")) {
                expenseCategoryId = c.id;
            }
            if (incomeCategoryId == null && c.type.equals("INCOME")) {
                incomeCategoryId = c.id;
            }
        }
        // Ordem: tipo e depois nome (despesas antes das receitas).
        assertEquals("EXPENSE", categories.get(0).type);
        assertEquals("INCOME", categories.get(categories.size() - 1).type);
        // Ordem alfabética pt-BR: "Alimentação" antes de "Assinaturas".
        assertEquals("Alimentação", categories.get(0).name);
    }

    @Test
    public void t3_contaRecorrenteViraLancamentosMensais() {
        String month = Dates.currentMonth();
        String start = month + "-10";
        RecurringBillInput req = Testing.bill("Aluguel", 1500, "EXPENSE", "MONTHLY", start, null, accountId, expenseCategoryId);
        billId = RecurringService.createRecurring(req).id;

        TransactionPage page = list(month);
        assertEquals(1, page.items.size());
        assertEquals(billId, page.items.get(0).recurringBillId);
        assertEquals(150000, page.summary.pendingExpense); // ocorrência nasce pendente

        // Próximo mês também gera a ocorrência, de forma idempotente.
        String next = Dates.plusMonthsYm(month, 1);
        assertEquals(1, list(next).items.size());
        assertEquals(1, list(next).items.size());

        // Alterar o valor atualiza as ocorrências pendentes.
        req.amount = 160000L;
        RecurringService.updateRecurring(billId, req);
        TransactionPage after = list(month);
        assertEquals(160000, after.items.get(0).amount);
        assertEquals(160000, list(next).items.get(0).amount);

        // Marcar como pago reflete no saldo da conta.
        TransactionsService.setPaid(after.items.get(0).id, true);
        assertEquals(-160000, CatalogService.listAccounts().get(0).balance);

        // Mudar o calendário recria só os pendentes; o pago fica.
        req.startDate = month + "-15";
        RecurringService.updateRecurring(billId, req);
        List<Transaction> moved = list(next).items;
        assertEquals(1, moved.size());
        assertEquals(next + "-15", moved.get(0).dueDate);
        List<Boolean> paid = new ArrayList<>();
        for (Transaction t : list(month).items) {
            if (billId.equals(t.recurringBillId)) {
                paid.add(t.paid);
            }
        }
        Collections.sort(paid);
        assertEquals(Arrays.asList(false, true), paid);
    }

    private static TransactionInput row(String description, double amount, String type, String dueDate) {
        return tx(description, amount, type, dueDate, true, accountId,
            type.equals("INCOME") ? incomeCategoryId : expenseCategoryId);
    }

    @Test
    public void t5_painelAgregaOMes() {
        TransactionsService.createTransaction(row("PAGTO ENEL DISTRIBUICAO", 189.9, "EXPENSE", "2026-10-03"));
        TransactionsService.createTransaction(row("SALARIO ACME LTDA", 4500, "INCOME", "2026-10-05"));
        TransactionsService.createTransaction(row("PADARIA", 52.3, "EXPENSE", "2026-10-07"));

        Dashboard d = DashboardService.buildDashboard("2026-10");
        assertEquals(450000, d.income);
        assertEquals(24220, d.expense);
        assertEquals(425780, d.balance);
        assertEquals(450000, d.paidIncome);
        assertTrue(d.byCategory.size() >= 1);
        assertEquals(100, d.byCategory.get(0).percent, 0);
        assertEquals(6, d.history.size());
        MonthTotal last = d.history.get(5);
        assertEquals("2026-10", last.month);
        assertEquals(450000, last.income);
        assertEquals(24220, last.expense);
        assertEquals("2026-05", d.history.get(0).month);
        assertEquals(1, d.accounts.size());
    }

    @Test
    public void t7_errosDeValidacaoVemPorCampo() {
        TransactionInput bad = new TransactionInput();
        bad.description = "";
        bad.amount = -1L;
        ApiException e = assertThrows(ApiException.class, () -> TransactionsService.createTransaction(bad));
        assertEquals(400, e.status);
        assertTrue(e.errors.containsKey("description"));
        assertTrue(e.errors.containsKey("amount"));
    }

    @Test
    public void t8_aluguelRecorrenteApareceEReceitasEntramNoResumo() {
        String pjId = CatalogService.createAccount(account("Conta PJ", "CHECKING", 0, "#1B5E3F")).id;
        String month = "2027-03";
        TransactionsService.createTransaction(tx("Cliente", 1000, "INCOME", month + "-01", true, pjId, incomeCategoryId));
        TransactionPage page = list(month);
        assertEquals(2, page.items.size());
        assertEquals(100000, page.summary.income);
        assertEquals(160000, page.summary.pendingExpense);
        long pjBalance = 0;
        for (Account a : CatalogService.listAccounts()) {
            if (a.id.equals(pjId)) {
                pjBalance = a.balance;
            }
        }
        assertEquals(100000, pjBalance);

        Dashboard dash = DashboardService.buildDashboard(month);
        assertEquals(100000, dash.income);
        assertEquals(160000, dash.expense);
        assertEquals(0, dash.transfers);
    }

    @Test
    public void t9_contasExcluirSoSemLancamentosArquivarTiraDaLista() {
        assert422(() -> CatalogService.deleteAccount(accountId));
        Account empty = CatalogService.createAccount(account("Vazia", "CASH", 1000, "#1B5E3F"));
        CatalogService.deleteAccount(empty.id);
        Account old = CatalogService.createAccount(account("Antiga", "SAVINGS", 1000, "#1B5E3F"));
        AccountInput archive = account("Antiga", "SAVINGS", 1000, "#1B5E3F");
        archive.archived = true;
        CatalogService.updateAccount(old.id, archive);
        for (Account a : CatalogService.listAccounts()) {
            assertFalse(a.id.equals(old.id));
        }
        List<Account> all = CatalogService.listAccounts(true);
        assertEquals(old.id, all.get(all.size() - 1).id); // arquivadas por último
    }

    @Test
    public void t9b_excluirVencimentoGeradoEleReaparece() {
        String next = Dates.plusMonthsYm(Dates.currentMonth(), 2);
        Transaction occ = null;
        for (Transaction t : list(next).items) {
            if (billId.equals(t.recurringBillId)) {
                occ = t;
            }
        }
        assertNotNull(occ);
        TransactionsService.deleteTransaction(occ.id);
        List<Transaction> again = new ArrayList<>();
        for (Transaction t : list(next).items) {
            if (billId.equals(t.recurringBillId)) {
                again.add(t);
            }
        }
        assertEquals(1, again.size());
        assertFalse(TransactionsService.getTransaction(again.get(0).id).paid);
    }
}
