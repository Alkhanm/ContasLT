package br.com.contaslt.transaction;

import br.com.contaslt.account.Account;
import br.com.contaslt.category.Category;
import br.com.contaslt.recurring.RecurringBill;
import br.com.contaslt.transaction.TransactionDtos.TransactionDto;

public final class TransactionMapper {

    private TransactionMapper() {
    }

    public static TransactionDto toDto(Transaction t) {
        Account a = t.getAccount();
        Category c = t.getCategory();
        RecurringBill r = t.getRecurringBill();
        return new TransactionDto(t.getId(), t.getDescription(), t.getAmount(), t.getType(), t.getDueDate(),
            t.isPaid(), t.getNotes(), a.getId(), a.getName(),
            c == null ? null : c.getId(), c == null ? null : c.getName(), c == null ? null : c.getColor(),
            c == null ? null : c.getIcon(),
            r == null ? null : r.getId(), t.getInstallmentNumber(),
            r == null ? null : r.getOccurrences(), t.getExternalId());
    }
}
