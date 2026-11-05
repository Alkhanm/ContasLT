package br.com.contaslt.services;

import static br.com.contaslt.services.Testing.account;
import static br.com.contaslt.services.Testing.card;
import static br.com.contaslt.services.Testing.cents;
import static br.com.contaslt.services.TransfersServiceTest.assertThrowsMessage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.CategoryReport;
import br.com.contaslt.dto.CategorySpending;
import br.com.contaslt.dto.ItemReport;
import br.com.contaslt.dto.ItemSpending;
import br.com.contaslt.dto.MonthValue;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.dto.TransactionInput;
import br.com.contaslt.dto.TransactionItemInput;
import br.com.contaslt.dto.TransferInput;

/** Porte de frontend/src/services/reports.test.ts. */
public class ReportsServiceTest {

    private String accountId;

    @Before
    public void setUp() {
        Testing.freshStore();
        accountId = Testing.basicCatalog().account.id;
    }

    private static String category(String name, String parentId) {
        return CatalogService.createCategory(Testing.category(name, "EXPENSE", "#3366FF", "tag", parentId)).id;
    }

    private static TransactionItemInput item(String description, double quantity, Double unitPrice, double total) {
        TransactionItemInput i = new TransactionItemInput();
        i.description = description;
        i.quantity = quantity;
        i.unitPrice = unitPrice == null ? null : cents(unitPrice);
        i.total = cents(total);
        return i;
    }

    private Transaction expense(String description, double amount, String date, String categoryId,
                                List<TransactionItemInput> items, String account) {
        TransactionInput in = Testing.tx(description, amount, "EXPENSE", date, true, account, categoryId);
        in.items = items;
        in.merchant = items.isEmpty() ? null : "Mercadinho";
        return TransactionsService.createTransaction(in).transaction;
    }

    private Transaction expense(String description, double amount, String date, String categoryId,
                                TransactionItemInput... items) {
        return expense(description, amount, date, categoryId, new ArrayList<>(Arrays.asList(items)), accountId);
    }

    private void income(String description, double amount, String date) {
        TransactionsService.createTransaction(Testing.tx(description, amount, "INCOME", date, true, accountId, null));
    }

    @Test
    public void categoriasAgrupamSubcategoriasSobAPaiComMesAnteriorEHistorico() {
        String mercado = category("Mercado", null);
        String padaria = category("Padaria", mercado);
        String lazer = category("Lazer", null);

        expense("Compra do mês", 300, "2034-05-02", mercado);
        expense("Pão", 50, "2034-05-10", padaria);
        expense("Cinema", 100, "2034-05-15", lazer);
        expense("Sem rumo", 40, "2034-05-20", null);
        expense("Mercado de abril", 200, "2034-04-05", mercado);
        expense("Mercado de janeiro", 120, "2034-01-05", mercado);
        income("Salário", 5000, "2034-05-05");
        // Fora do relatório: compra no cartão e transferência entre contas.
        Account cardAcc = CatalogService.createAccount(card("Cartão", 0, "#000000", 5, 12));
        expense("No cartão", 999, "2034-05-03", mercado, new ArrayList<>(), cardAcc.id);
        Account savings = CatalogService.createAccount(account("Poupança", "SAVINGS", 0, "#000000"));
        TransferInput tr = new TransferInput();
        tr.fromAccountId = accountId;
        tr.toAccountId = savings.id;
        tr.amount = cents(777);
        tr.date = "2034-05-04";
        tr.paid = true;
        TransfersService.createTransfer(tr);

        CategoryReport report = ReportsService.categoryReport("2034-05");
        assertEquals("2034-05", report.month);
        assertEquals(49000, report.total);
        assertEquals(20000, report.previousTotal);
        assertEquals(4, report.count);
        List<CategorySpending> items = report.items;
        assertEquals(3, items.size());

        CategorySpending first = items.get(0);
        assertEquals("Mercado", first.name);
        assertEquals(mercado, first.categoryId);
        assertEquals(35000, first.total);
        assertEquals(2, first.count);
        assertEquals(Long.valueOf(20000), first.previousTotal);
        assertEquals(71.4, first.percent, 0);
        // Subcategoria com gasto: Padaria e "Geral" (o que foi lançado direto em Mercado).
        assertEquals(2, first.children.size());
        assertEquals("Geral", first.children.get(0).name);
        assertEquals(mercado, first.children.get(0).categoryId);
        assertEquals(30000, first.children.get(0).total);
        assertEquals(85.7, first.children.get(0).percent, 0);
        assertNull(first.children.get(0).previousTotal);
        assertEquals("Padaria", first.children.get(1).name);
        assertEquals(padaria, first.children.get(1).categoryId);
        assertEquals(5000, first.children.get(1).total);
        // Seis meses: dezembro de 2033 a maio de 2034.
        List<MonthValue> history = first.history;
        assertEquals(6, history.size());
        assertEquals("2033-12", history.get(0).month);
        assertEquals(12000, history.get(1).total);
        assertEquals(20000, history.get(4).total);
        assertEquals(35000, history.get(5).total);

        assertEquals("Lazer", items.get(1).name);
        assertEquals(0, items.get(1).children.size()); // sem subcategoria: não desdobra
        assertEquals("Sem categoria", items.get(2).name);
        assertNull(items.get(2).categoryId);
        assertEquals("#9CA3AF", items.get(2).color);
        assertEquals("circle-dashed", items.get(2).icon);
    }

    @Test
    public void categoriasIncluemOsVencimentosDeRecorrenteDoMes() {
        String casa = category("Casa", null);
        RecurringService.createRecurring(Testing.bill("Aluguel", 1500, "EXPENSE", "MONTHLY", "2034-01-10", null, accountId, casa));
        CategoryReport report = ReportsService.categoryReport("2034-03");
        assertEquals(150000, report.total);
        assertEquals(150000, report.previousTotal);
        List<Long> totals = new ArrayList<>();
        for (MonthValue v : report.items.get(0).history) {
            totals.add(v.total);
        }
        assertEquals(Arrays.asList(0L, 0L, 0L, 150000L, 150000L, 150000L), totals);
    }

    @Test
    public void itensAgrupamLinhasDeComprovantePeloNomeComMesesEHistoricoDePreco() {
        String mercado = category("Mercado", null);
        String padaria = category("Padaria", mercado);

        Transaction first = expense("Supermercado", 20, "2034-02-03", mercado,
            item("Leite Integral 1L", 2, 4.5, 9.0),
            item("Arroz 5kg", 1, null, 11.0));
        expense("Supermercado", 25.4, "2034-03-07", padaria,
            item("LEITE INTEGRAL 1L", 3, 5.2, 15.6),
            item("Pão francês", 0.5, 19.6, 9.8));
        expense("Fora do período", 9, "2033-06-01", mercado, item("Leite integral 1l", 1, 3.9, 3.9));

        ItemReport report = ReportsService.itemReport("2034-01", "2034-03", null, null);
        assertEquals("2034-01", report.from);
        assertEquals("2034-03", report.to);
        assertEquals(4540, report.total);
        assertEquals(4, report.count);
        List<ItemSpending> items = report.items;
        assertEquals(3, items.size());

        ItemSpending leite = items.get(0);
        assertEquals("leite integral l", leite.key);
        assertEquals("LEITE INTEGRAL 1L", leite.description); // nome da compra mais recente
        assertEquals("Mercado › Padaria", leite.categoryName); // categoria da compra mais recente
        assertEquals(padaria, leite.categoryId);
        assertEquals(2, leite.count);
        assertEquals(5, leite.quantity, 0);
        assertEquals(2460, leite.total);
        assertEquals(Long.valueOf(450), leite.firstUnitPrice);
        assertEquals(Long.valueOf(520), leite.lastUnitPrice);
        assertEquals(Long.valueOf(450), leite.minUnitPrice);
        assertEquals(Long.valueOf(520), leite.maxUnitPrice);
        assertEquals(2, leite.months.size());
        assertEquals("2034-02", leite.months.get(0).month);
        assertEquals(1560, leite.months.get(1).total);
        assertEquals(2, leite.purchases.size());
        assertEquals("2034-02-03", leite.purchases.get(0).date);
        assertEquals(first.id, leite.purchases.get(0).transactionId);
        assertEquals("Mercadinho", leite.purchases.get(0).merchant);

        // Sem preço unitário: total ÷ quantidade.
        ItemSpending arroz = items.get(1);
        assertEquals("Arroz 5kg", arroz.description);
        assertEquals(Long.valueOf(1100), arroz.lastUnitPrice);

        // Filtro pela categoria pai inclui a subcategoria; pela subcategoria, só ela. Busca pelo nome.
        assertEquals(3, ReportsService.itemReport("2034-01", "2034-03", mercado, null).items.size());
        ItemReport bySub = ReportsService.itemReport("2034-01", "2034-03", padaria, null);
        assertEquals(2, bySub.items.size());
        assertEquals(1, bySub.items.get(0).count);
        ItemReport bySearch = ReportsService.itemReport("2034-01", "2034-03", null, "pão");
        assertEquals(1, bySearch.items.size());
        assertEquals("Pão francês", bySearch.items.get(0).description);

        assertThrowsMessage("O mês inicial não pode ser depois do final",
            () -> ReportsService.itemReport("2034-04", "2034-03", null, null));
        assertThrowsMessage("Dados inválidos", () -> ReportsService.itemReport("2034-4", null, null, null));
    }

    @Test
    public void itensPrecoUnitarioCalculadoQuandoFaltaEPeriodoPadraoDe12Meses() {
        expense("Feira", 10, "2034-02-03", null, item("Banana", 3, null, 10));
        ItemReport r = ReportsService.itemReport("2034-02", "2034-02", null, null);
        assertEquals(Long.valueOf(333), r.items.get(0).lastUnitPrice);
        assertNull(r.items.get(0).categoryName);
        assertEquals("#9CA3AF", r.items.get(0).color);
        ItemReport def = ReportsService.itemReport(null, "2034-12", null, null);
        assertEquals("2034-01", def.from);
        assertEquals(1, def.items.size());
    }
}
