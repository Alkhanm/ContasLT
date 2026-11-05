package br.com.contaslt.services;

import br.com.contaslt.data.LocalStore;
import br.com.contaslt.data.MemoryPersistence;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.domain.Money;
import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.AccountInput;
import br.com.contaslt.dto.Category;
import br.com.contaslt.dto.CategoryInput;
import br.com.contaslt.dto.RecurringBillInput;
import br.com.contaslt.dto.TransactionInput;

/** Ajudantes dos testes de serviço (porte de frontend/src/services/testing.ts, mais construtores dos DTOs). */
public final class Testing {

    private Testing() {
    }

    /** Store novo, vazio, ligado aos serviços. Use no @Before. */
    public static LocalStore freshStore() {
        LocalStore s = new LocalStore(new MemoryPersistence());
        s.load();
        Ctx.useStoreForTests(s);
        RecurringService.invalidateEnsured();
        Dates.resetForTests();
        return s;
    }

    public static final class Catalog {
        public final Account account;
        public final Category expense;
        public final Category income;

        Catalog(Account account, Category expense, Category income) {
            this.account = account;
            this.expense = expense;
            this.income = income;
        }
    }

    /** Conta corrente e duas categorias (despesa e receita) para os testes. */
    public static Catalog basicCatalog() {
        Account account = CatalogService.createAccount(account("Conta", "CHECKING", 0, "#1B5E3F"));
        Category expense = CatalogService.createCategory(category("Mercado", "EXPENSE", "#C98A2B", "shopping-cart", null));
        Category income = CatalogService.createCategory(category("Salário", "INCOME", "#1B5E3F", "briefcase", null));
        return new Catalog(account, expense, income);
    }

    /** Reais → centavos (os testes do PWA escrevem valores em reais). */
    public static long cents(double reais) {
        return Money.toCents(reais);
    }

    public static AccountInput account(String name, String type, long initialBalanceCents, String color) {
        AccountInput a = new AccountInput();
        a.name = name;
        a.type = type;
        a.initialBalance = initialBalanceCents;
        a.color = color;
        return a;
    }

    public static AccountInput card(String name, long initialBalanceCents, String color, Integer closingDay, Integer dueDay) {
        AccountInput a = account(name, "CREDIT_CARD", initialBalanceCents, color);
        a.closingDay = closingDay;
        a.dueDay = dueDay;
        return a;
    }

    public static CategoryInput category(String name, String type, String color, String icon, String parentId) {
        CategoryInput c = new CategoryInput();
        c.name = name;
        c.type = type;
        c.color = color;
        c.icon = icon;
        c.parentId = parentId;
        return c;
    }

    public static TransactionInput tx(String description, double reais, String type, String dueDate, boolean paid,
                                      String accountId, String categoryId) {
        TransactionInput t = new TransactionInput();
        t.description = description;
        t.amount = cents(reais);
        t.type = type;
        t.dueDate = dueDate;
        t.paid = paid;
        t.notes = null;
        t.accountId = accountId;
        t.categoryId = categoryId;
        return t;
    }

    public static RecurringBillInput bill(String description, double reais, String type, String frequency, String startDate,
                                          Integer occurrences, String accountId, String categoryId) {
        RecurringBillInput b = new RecurringBillInput();
        b.description = description;
        b.amount = cents(reais);
        b.type = type;
        b.frequency = frequency;
        b.startDate = startDate;
        b.endDate = null;
        b.occurrences = occurrences;
        b.accountId = accountId;
        b.categoryId = categoryId;
        b.notes = null;
        return b;
    }
}
