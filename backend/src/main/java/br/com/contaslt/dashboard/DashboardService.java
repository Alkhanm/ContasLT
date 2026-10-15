package br.com.contaslt.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.contaslt.account.AccountDtos.AccountDto;
import br.com.contaslt.account.AccountService;
import br.com.contaslt.category.Category;
import br.com.contaslt.common.TransactionType;
import br.com.contaslt.dashboard.DashboardDtos.CategoryTotal;
import br.com.contaslt.dashboard.DashboardDtos.Dashboard;
import br.com.contaslt.dashboard.DashboardDtos.MonthTotal;
import br.com.contaslt.recurring.RecurringBillService;
import br.com.contaslt.transaction.Transaction;
import br.com.contaslt.transaction.TransactionMapper;
import br.com.contaslt.transaction.TransactionRepository;
import br.com.contaslt.user.User;

@Service
public class DashboardService {

    private static final int HISTORY_MONTHS = 6;

    private final TransactionRepository transactions;
    private final AccountService accounts;
    private final RecurringBillService recurring;

    public DashboardService(TransactionRepository transactions, AccountService accounts, RecurringBillService recurring) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.recurring = recurring;
    }

    @Transactional
    public Dashboard build(User user, YearMonth month) {
        LocalDate today = LocalDate.now();
        YearMonth historyStart = month.minusMonths(HISTORY_MONTHS - 1);
        for (YearMonth ym = historyStart; !ym.isAfter(month); ym = ym.plusMonths(1)) {
            recurring.ensureOccurrences(user, ym);
        }
        // Também materializa o mês atual, para que vencimentos próximos apareçam mesmo ao ver meses passados.
        recurring.ensureOccurrences(user, YearMonth.from(today));

        List<Transaction> items = transactions.findByUserAndPeriod(user.getId(), month.atDay(1), month.atEndOfMonth());
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        BigDecimal paidIncome = BigDecimal.ZERO;
        BigDecimal paidExpense = BigDecimal.ZERO;
        Map<Category, List<Transaction>> byCategory = new LinkedHashMap<>();
        List<Transaction> uncategorized = new ArrayList<>();
        for (Transaction t : items) {
            if (t.getType() == TransactionType.INCOME) {
                income = income.add(t.getAmount());
                if (t.isPaid()) {
                    paidIncome = paidIncome.add(t.getAmount());
                }
            } else {
                expense = expense.add(t.getAmount());
                if (t.isPaid()) {
                    paidExpense = paidExpense.add(t.getAmount());
                }
                if (t.getCategory() == null) {
                    uncategorized.add(t);
                } else {
                    byCategory.computeIfAbsent(t.getCategory(), k -> new ArrayList<>()).add(t);
                }
            }
        }

        List<CategoryTotal> categoryTotals = new ArrayList<>();
        for (Map.Entry<Category, List<Transaction>> e : byCategory.entrySet()) {
            BigDecimal total = sum(e.getValue());
            categoryTotals.add(new CategoryTotal(e.getKey().getId(), e.getKey().getName(), e.getKey().getColor(),
                e.getKey().getIcon(), total, percent(total, expense), e.getValue().size()));
        }
        if (!uncategorized.isEmpty()) {
            BigDecimal total = sum(uncategorized);
            categoryTotals.add(new CategoryTotal(null, "Sem categoria", "#9CA3AF", "circle-dashed", total,
                percent(total, expense), uncategorized.size()));
        }
        categoryTotals.sort(Comparator.comparing(CategoryTotal::total).reversed());

        List<AccountDto> accountList = accounts.list(user.getId(), false);
        BigDecimal currentBalance = accountList.stream().map(AccountDto::balance).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<MonthTotal> history = new ArrayList<>();
        List<Transaction> historyItems = transactions.findByUserAndPeriod(user.getId(), historyStart.atDay(1),
            month.atEndOfMonth());
        for (YearMonth ym = historyStart; !ym.isAfter(month); ym = ym.plusMonths(1)) {
            BigDecimal inc = BigDecimal.ZERO;
            BigDecimal exp = BigDecimal.ZERO;
            for (Transaction t : historyItems) {
                if (YearMonth.from(t.getDueDate()).equals(ym)) {
                    if (t.getType() == TransactionType.INCOME) {
                        inc = inc.add(t.getAmount());
                    } else {
                        exp = exp.add(t.getAmount());
                    }
                }
            }
            history.add(new MonthTotal(ym.toString(), inc, exp));
        }

        return new Dashboard(month.toString(), income, expense, income.subtract(expense), paidIncome, paidExpense,
            income.subtract(paidIncome), expense.subtract(paidExpense), currentBalance, accountList, categoryTotals,
            transactions.findOverdue(user.getId(), today).stream().map(TransactionMapper::toDto).toList(),
            transactions.findUpcoming(user.getId(), today, today.plusDays(7)).stream().map(TransactionMapper::toDto).toList(),
            history);
    }

    private static BigDecimal sum(List<Transaction> list) {
        return list.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal total) {
        if (total.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }
}
