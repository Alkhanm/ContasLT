package br.com.contaslt.dashboard;

import java.math.BigDecimal;
import java.util.List;

import br.com.contaslt.account.AccountDtos.AccountDto;
import br.com.contaslt.transaction.TransactionDtos.TransactionDto;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    public record CategoryTotal(Long categoryId, String name, String color, String icon, BigDecimal total,
            BigDecimal percent, int count) {
    }

    public record MonthTotal(String month, BigDecimal income, BigDecimal expense) {
    }

    public record Dashboard(String month, BigDecimal income, BigDecimal expense, BigDecimal balance,
            BigDecimal paidIncome, BigDecimal paidExpense, BigDecimal pendingIncome, BigDecimal pendingExpense,
            BigDecimal currentBalance, List<AccountDto> accounts, List<CategoryTotal> byCategory,
            List<TransactionDto> overdue, List<TransactionDto> upcoming, List<MonthTotal> history) {
    }
}
