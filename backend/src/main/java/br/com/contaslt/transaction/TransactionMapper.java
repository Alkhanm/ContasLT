package br.com.contaslt.transaction;

import br.com.contaslt.account.Account;
import br.com.contaslt.category.Category;
import br.com.contaslt.recurring.RecurringBill;
import br.com.contaslt.transfer.Transfer;
import br.com.contaslt.transaction.TransactionDtos.ItemDto;
import br.com.contaslt.transaction.TransactionDtos.TransactionDto;

public final class TransactionMapper {

    private TransactionMapper() {
    }

    public static TransactionDto toDto(Transaction t) {
        Account a = t.getAccount();
        Category c = t.getCategory();
        RecurringBill r = t.getRecurringBill();
        Transfer tr = t.getTransfer();
        Transaction peer = tr == null ? null : tr.peerOf(t);
        Account card = t.getCardAccount();
        return new TransactionDto(t.getId(), t.getDescription(), t.getAmount(), t.getType(), t.getDueDate(),
            t.isPaid(), t.getNotes(), a.getId(), a.getName(),
            c == null ? null : c.getId(), c == null ? null : c.getName(), c == null ? null : c.getColor(),
            c == null ? null : c.getIcon(),
            r == null ? null : r.getId(), t.getInstallmentNumber(),
            r == null ? null : r.getOccurrences(),
            r == null ? null : t.getScheduledDate(), r == null ? null : r.getAmount(), t.getExternalId(),
            tr == null ? null : tr.getId(), peer == null ? null : peer.getId(),
            peer == null ? null : peer.getAccount().getId(), peer == null ? null : peer.getAccount().getName(),
            t.getMerchant(), t.getReceiptFile(), t.getReceiptAccessKey(),
            t.getImportBatch() == null ? null : t.getImportBatch().getId(),
            t.getItems().stream().map(i -> new ItemDto(i.getId(), i.getDescription(), i.getQuantity(), i.getUnitPrice(),
                i.getTotal())).toList(),
            card == null ? null : card.getId(), card == null ? null : card.getName(), t.getCardInvoiceMonth(), null);
    }
}
