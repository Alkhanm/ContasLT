package br.com.contaslt.services;

import static br.com.contaslt.services.Testing.account;
import static br.com.contaslt.services.Testing.tx;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import br.com.contaslt.data.ApiException;
import br.com.contaslt.dto.Category;
import br.com.contaslt.dto.CategoryInput;
import br.com.contaslt.dto.CategoryTotal;
import br.com.contaslt.dto.TransactionFilter;

/** Porte de frontend/src/services/catalog.test.ts (SubcategoryTest). */
public class CatalogServiceTest {

    private static final String MONTH = "2031-03";

    private String accountId;
    private String casa;
    private String energia;
    private String agua;

    private static CategoryInput payload(String name, String type, String parentId) {
        return Testing.category(name, type, "#3D8361", "tag", parentId);
    }

    @Before
    public void setUp() {
        Testing.freshStore();
        accountId = CatalogService.createAccount(account("Conta principal", "CHECKING", 0, "#1B5E3F")).id;
        casa = CatalogService.createCategory(payload("Contas da casa", "EXPENSE", null)).id;
        energia = CatalogService.createCategory(payload("Energia", "EXPENSE", casa)).id;
        agua = CatalogService.createCategory(payload("Água", "INCOME", casa)).id; // tipo errado de propósito: segue o pai
    }

    private void transaction(String description, double amount, String categoryId) {
        TransactionsService.createTransaction(tx(description, amount, "EXPENSE", MONTH + "-10", true, accountId, categoryId));
    }

    private static Category find(String id, boolean includeArchived) {
        for (Category c : CatalogService.listCategories(includeArchived)) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    private static Category find(String id) {
        return find(id, false);
    }

    static void assert422(Runnable r) {
        ApiException e = assertThrows(ApiException.class, r::run);
        assertEquals(422, e.status);
    }

    private static TransactionFilter filter(String month) {
        TransactionFilter f = new TransactionFilter();
        f.month = month;
        return f;
    }

    @Test
    public void subcategoriaSegueOTipoDoPaiESoHaUmNivel() {
        assertEquals("EXPENSE", find(agua).type);
        assertEquals(casa, find(agua).parentId);
        assertNull(find(casa).parentId);

        assert422(() -> CatalogService.createCategory(payload("Neta", "EXPENSE", energia)));
        assert422(() -> CatalogService.updateCategory(casa, payload("Contas da casa", "EXPENSE", casa)));
        assert422(() -> CatalogService.updateCategory(casa, payload("Contas da casa", "EXPENSE", energia)));
    }

    @Test
    public void filtrarPeloPaiIncluiAsFilhasEPainelAgrupaPeloPai() {
        transaction("Enel", 200, energia);
        transaction("Saae", 80, agua);
        transaction("Condomínio", 500, casa);
        transaction("Outro", 10, null);

        TransactionFilter f = filter(MONTH);
        f.categoryId = casa;
        assertEquals(3, TransactionsService.listTransactions(f).items.size());

        f.categoryId = energia;
        var byChild = TransactionsService.listTransactions(f).items;
        assertEquals(1, byChild.size());
        assertEquals("Enel", byChild.get(0).description);

        TransactionFilter u = filter(MONTH);
        u.uncategorized = true;
        var none = TransactionsService.listTransactions(u).items;
        assertEquals(1, none.size());
        assertEquals("Outro", none.get(0).description);

        List<CategoryTotal> dash = DashboardService.buildDashboard(MONTH).byCategory;
        assertEquals(2, dash.size()); // Contas da casa (780) e Sem categoria (10)
        assertEquals(casa, dash.get(0).categoryId);
        assertEquals(78000, dash.get(0).total);
        assertEquals(3, dash.get(0).count);
        assertEquals(98.7, dash.get(0).percent, 0);
        assertNull(dash.get(1).categoryId);
    }

    @Test
    public void paiComFilhasNaoPodeSerExcluidoEArquivarPassaParaAsFilhas() {
        assert422(() -> CatalogService.deleteCategory(casa));

        CategoryInput archived = payload("Contas da casa", "EXPENSE", null);
        archived.archived = true;
        CatalogService.updateCategory(casa, archived);
        assertTrue(find(energia, true).archived);
        assertTrue(find(agua, true).archived);

        CatalogService.deleteCategory(energia);
        assertNull(find(energia, true));
    }

    @Test
    public void regrasComplementaresDeCategorias() {
        transaction("Enel", 200, energia);
        assert422(() -> CatalogService.deleteCategory(energia));

        CategoryInput archived = payload("Contas da casa", "EXPENSE", null);
        archived.archived = true;
        CatalogService.updateCategory(casa, archived);
        CategoryInput unarchived = payload("Água", "EXPENSE", casa);
        unarchived.archived = false;
        CatalogService.updateCategory(agua, unarchived);
        assertFalse(find(casa).archived);
        assertTrue(find(energia, true).archived);

        String lazer = CatalogService.createCategory(payload("Lazer", "EXPENSE", null)).id;
        String cinema = CatalogService.createCategory(payload("Cinema", "EXPENSE", lazer)).id;
        CatalogService.updateCategory(lazer, payload("Lazer", "INCOME", null));
        assertEquals("INCOME", find(cinema).type);

        CategoryInput bad = payload("", "EXPENSE", null);
        bad.color = "azul";
        ApiException e = assertThrows(ApiException.class, () -> CatalogService.createCategory(bad));
        assertEquals(400, e.status);
        assertTrue(e.errors.containsKey("name"));
        assertTrue(e.errors.containsKey("color"));

        CategoryInput noIcon = payload("Sem ícone", "EXPENSE", null);
        noIcon.icon = "  ";
        assertEquals("tag", CatalogService.createCategory(noIcon).icon);
    }
}
