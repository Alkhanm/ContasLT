package br.com.contaslt.services;

import static br.com.contaslt.services.Testing.tx;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import br.com.contaslt.dto.Dashboard;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.dto.TransactionFilter;
import br.com.contaslt.dto.TransactionPage;

/** Porte de frontend/src/services/transactions.test.ts. */
public class TransactionsServiceTest {

    @Before
    public void setUp() {
        Testing.freshStore();
    }

    private static TransactionPage list(String month) {
        TransactionFilter f = new TransactionFilter();
        f.month = month;
        return TransactionsService.listTransactions(f);
    }

    @Test
    public void listaDoMesComResumoSaldoPorDiaEVencimentosGerados() {
        Testing.Catalog c = Testing.basicCatalog();
        String acc = c.account.id;
        TransactionsService.createTransaction(tx("Salário", 5000, "INCOME", "2026-03-05", true, acc, c.income.id));
        TransactionsService.createTransaction(tx("Feira", 120.5, "EXPENSE", "2026-03-06", true, acc, c.expense.id));
        RecurringService.createRecurring(Testing.bill("Aluguel", 1500, "EXPENSE", "MONTHLY", "2026-01-10", null, acc, c.expense.id));

        TransactionPage page = list("2026-03");
        List<String> descriptions = new ArrayList<>();
        for (Transaction t : page.items) {
            descriptions.add(t.description);
        }
        assertEquals(Arrays.asList("Aluguel", "Feira", "Salário"), descriptions);
        assertFalse(page.items.get(0).paid);
        assertEquals("2026-03-10", page.items.get(0).scheduledDate);
        assertEquals(500000, page.summary.income);
        assertEquals(12050, page.summary.expense);
        assertEquals(487950, page.summary.balance);
        assertEquals(150000, page.summary.pendingExpense);
        assertEquals(337950, page.summary.projectedBalance);
        assertEquals(31, page.dayBalances.size());
        assertEquals("2026-03-06", page.dayBalances.get(5).date);
        assertEquals(487950, page.dayBalances.get(5).balance);

        // Ver o mês de novo não duplica o vencimento.
        assertEquals(3, list("2026-03").items.size());

        Dashboard dash = DashboardService.buildDashboard("2026-03");
        assertEquals(162050, dash.expense);
        assertEquals("Mercado", dash.byCategory.get(0).name);
        assertEquals(6, dash.history.size());
    }
}
