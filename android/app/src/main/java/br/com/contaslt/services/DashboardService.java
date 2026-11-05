package br.com.contaslt.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.com.contaslt.data.LocalStore;
import br.com.contaslt.data.schema.CategoryRec;
import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.data.schema.TransactionRec;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.domain.Text;
import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.CategoryTotal;
import br.com.contaslt.dto.Dashboard;
import br.com.contaslt.dto.MonthTotal;

/** Painel do mês (porte de frontend/src/services/dashboard.ts). */
public final class DashboardService {

    private static final int HISTORY_MONTHS = 6;

    private DashboardService() {
    }

    /** Percentual com uma casa, arredondado como o HALF_UP do backend. */
    public static double percent(long part, long total) {
        if (total == 0) {
            return 0;
        }
        return Math.round((part * 1000.0) / total) / 10.0;
    }

    private static long sum(List<TransactionRec> list) {
        long s = 0;
        for (TransactionRec t : list) {
            s += t.amount;
        }
        return s;
    }

    public static Dashboard buildDashboard(String month) {
        String now = Dates.today();
        String historyStart = Dates.plusMonthsYm(month, -(HISTORY_MONTHS - 1));
        for (String ym : Dates.monthsBetween(historyStart, month)) {
            RecurringService.ensureOccurrences(ym);
        }
        // Também o mês atual, para que vencimentos próximos apareçam mesmo vendo meses passados.
        RecurringService.ensureOccurrences(Dates.currentMonth());

        LocalStore s = Ctx.db();
        List<TransactionRec> cash = new ArrayList<>();
        for (TransactionRec t : s.list(Entity.TRANSACTION)) {
            if (!CardsService.isCreditCard(s.get(Entity.ACCOUNT, t.accountId))) {
                cash.add(t);
            }
        }
        String from = Dates.firstDay(month);
        String to = Dates.endOfMonth(month);
        String historyFrom = Dates.firstDay(historyStart);
        List<TransactionRec> historyItems = new ArrayList<>();
        for (TransactionRec t : cash) {
            if (t.dueDate.compareTo(historyFrom) >= 0 && t.dueDate.compareTo(to) <= 0) {
                historyItems.add(t);
            }
        }
        List<TransactionRec> items = new ArrayList<>();
        for (TransactionRec t : historyItems) {
            if (t.dueDate.compareTo(from) >= 0) {
                items.add(t);
            }
        }

        long income = 0, expense = 0, paidIncome = 0, paidExpense = 0, transfers = 0;
        Map<String, CategoryRec> roots = new LinkedHashMap<>();
        Map<String, List<TransactionRec>> byCategory = new LinkedHashMap<>();
        List<TransactionRec> uncategorized = new ArrayList<>();
        for (TransactionRec t : items) {
            if (Text.present(t.transferId)) {
                // Movimentação interna: não é receita nem despesa. Conta-se só a perna de saída.
                if ("EXPENSE".equals(t.type)) {
                    transfers += t.amount;
                }
                continue;
            }
            if ("INCOME".equals(t.type)) {
                income += t.amount;
                if (t.paid) {
                    paidIncome += t.amount;
                }
            } else {
                expense += t.amount;
                if (t.paid) {
                    paidExpense += t.amount;
                }
                CategoryRec c = s.get(Entity.CATEGORY, t.categoryId);
                if (c == null) {
                    uncategorized.add(t);
                } else {
                    // Subcategorias somam na categoria pai; o clique no painel filtra pai + filhas.
                    CategoryRec root = CatalogService.rootOf(c);
                    roots.putIfAbsent(root.id, root);
                    byCategory.computeIfAbsent(root.id, k -> new ArrayList<>()).add(t);
                }
            }
        }

        List<CategoryTotal> totals = new ArrayList<>();
        for (Map.Entry<String, List<TransactionRec>> e : byCategory.entrySet()) {
            CategoryRec c = roots.get(e.getKey());
            long total = sum(e.getValue());
            totals.add(categoryTotal(c.id, c.name, c.color, c.icon, total, percent(total, expense), e.getValue().size()));
        }
        if (!uncategorized.isEmpty()) {
            long total = sum(uncategorized);
            totals.add(categoryTotal(null, "Sem categoria", "#9CA3AF", "circle-dashed", total, percent(total, expense),
                uncategorized.size()));
        }
        totals.sort((a, b) -> Long.compare(b.total, a.total));

        List<Account> accounts = CatalogService.listAccounts(false);
        // Cartão de crédito não é caixa: o saldo total soma só as contas de dinheiro.
        long currentBalance = 0;
        for (Account a : accounts) {
            if (!"CREDIT_CARD".equals(a.type)) {
                currentBalance += a.balance;
            }
        }

        List<MonthTotal> history = new ArrayList<>();
        for (String ym : Dates.monthsBetween(historyStart, month)) {
            long inc = 0, exp = 0;
            for (TransactionRec t : historyItems) {
                if (Text.present(t.transferId) || !Dates.monthOf(t.dueDate).equals(ym)) {
                    continue;
                }
                if ("INCOME".equals(t.type)) {
                    inc += t.amount;
                } else {
                    exp += t.amount;
                }
            }
            MonthTotal mt = new MonthTotal();
            mt.month = ym;
            mt.income = inc;
            mt.expense = exp;
            history.add(mt);
        }

        Comparator<TransactionRec> byDueAsc = (a, b) -> a.dueDate.compareTo(b.dueDate);
        String week = Dates.plusDays(now, 7);
        List<TransactionRec> overdue = new ArrayList<>();
        List<TransactionRec> upcoming = new ArrayList<>();
        for (TransactionRec t : cash) {
            if (t.paid) {
                continue;
            }
            if (t.dueDate.compareTo(now) < 0) {
                overdue.add(t);
            } else if (t.dueDate.compareTo(week) <= 0) {
                upcoming.add(t);
            }
        }
        overdue.sort(byDueAsc);
        upcoming.sort(byDueAsc);

        Dashboard d = new Dashboard();
        d.month = month;
        d.income = income;
        d.expense = expense;
        d.balance = income - expense;
        d.paidIncome = paidIncome;
        d.paidExpense = paidExpense;
        d.pendingIncome = income - paidIncome;
        d.pendingExpense = expense - paidExpense;
        // Dashboard.transfers é int no DTO (centavos); ver relatório do porte.
        d.transfers = transfers;
        d.currentBalance = currentBalance;
        d.accounts = accounts;
        d.byCategory = totals;
        for (TransactionRec t : overdue) {
            d.overdue.add(Mappers.transactionDto(t));
        }
        for (TransactionRec t : upcoming) {
            d.upcoming.add(Mappers.transactionDto(t));
        }
        d.history = history;
        return d;
    }

    private static CategoryTotal categoryTotal(String id, String name, String color, String icon, long total,
                                               double percent, int count) {
        CategoryTotal ct = new CategoryTotal();
        ct.categoryId = id;
        ct.name = name;
        ct.color = color;
        ct.icon = icon;
        ct.total = total;
        ct.percent = percent;
        ct.count = count;
        return ct;
    }
}
