package br.com.contaslt.services;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import br.com.contaslt.data.LocalStore;
import br.com.contaslt.data.schema.AccountRec;
import br.com.contaslt.data.schema.CategoryRec;
import br.com.contaslt.data.schema.CategoryRuleRec;
import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.data.schema.ItemRec;
import br.com.contaslt.data.schema.RecurringBillRec;
import br.com.contaslt.data.schema.TransactionRec;
import br.com.contaslt.data.schema.TransferRec;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.domain.Recurrence;
import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.Category;
import br.com.contaslt.dto.CategoryRule;
import br.com.contaslt.dto.RecurringBill;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.dto.TransactionItem;

/**
 * Registros do aparelho → DTOs das telas (porte de frontend/src/services/mappers.ts). Diferente do PWA, os valores
 * continuam em centavos nos DTOs.
 */
public final class Mappers {

    private Mappers() {
    }

    public static Category categoryDto(CategoryRec c) {
        Category d = new Category();
        d.id = c.id;
        d.name = c.name;
        d.type = c.type;
        d.color = c.color;
        d.icon = c.icon;
        d.archived = c.archived;
        d.parentId = c.parentId;
        return d;
    }

    /** Movimento pago líquido por conta (receitas − despesas), de uma vez para todas. */
    public static Map<String, Long> paidMovementsByAccount() {
        return paidMovementsByAccount(Ctx.db().list(Entity.TRANSACTION));
    }

    public static Map<String, Long> paidMovementsByAccount(List<TransactionRec> txs) {
        Map<String, Long> out = new HashMap<>();
        for (TransactionRec t : txs) {
            if (!t.paid) {
                continue;
            }
            Long prev = out.get(t.accountId);
            out.put(t.accountId, (prev == null ? 0 : prev) + ("INCOME".equals(t.type) ? t.amount : -t.amount));
        }
        return out;
    }

    /**
     * Saldo: inicial + movimentos pagos (inclusive pernas de transferência). Cartão de crédito: saldo inicial menos a
     * dívida em aberto (compras cuja fatura ainda não teve pagamento registrado).
     */
    public static long accountBalance(AccountRec a, Map<String, Long> movements, List<TransactionRec> txs) {
        if (CardsService.isCreditCard(a)) {
            return a.initialBalance - CardsService.openDebt(a, txs);
        }
        Long m = movements.get(a.id);
        return a.initialBalance + (m == null ? 0 : m);
    }

    public static long accountBalance(AccountRec a, Map<String, Long> movements) {
        return accountBalance(a, movements, Ctx.db().list(Entity.TRANSACTION));
    }

    public static Account accountDto(AccountRec a, long balanceCents) {
        Account d = new Account();
        d.id = a.id;
        d.name = a.name;
        d.type = a.type;
        d.initialBalance = a.initialBalance;
        d.color = a.color;
        d.archived = a.archived;
        d.balance = balanceCents;
        d.closingDay = a.closingDay;
        d.dueDay = a.dueDay;
        return d;
    }

    public static CategoryRule ruleDto(CategoryRuleRec r) {
        CategoryRec c = Ctx.db().get(Entity.CATEGORY, r.categoryId);
        if (c == null) {
            return null;
        }
        CategoryRule d = new CategoryRule();
        d.id = r.id;
        d.keyword = r.keyword;
        d.categoryId = c.id;
        d.categoryName = c.name;
        d.categoryColor = c.color;
        d.categoryIcon = c.icon;
        return d;
    }

    public static RecurringBill recurringDto(RecurringBillRec b) {
        AccountRec a = Ctx.db().get(Entity.ACCOUNT, b.accountId);
        CategoryRec c = Ctx.db().get(Entity.CATEGORY, b.categoryId);
        RecurringBill d = new RecurringBill();
        d.id = b.id;
        d.description = b.description;
        d.amount = b.amount;
        d.type = b.type;
        d.frequency = b.frequency;
        d.startDate = b.startDate;
        d.endDate = b.endDate;
        d.occurrences = b.occurrences;
        d.accountId = a == null ? null : a.id;
        d.accountName = a == null ? null : a.name;
        d.categoryId = c == null ? null : c.id;
        d.categoryName = c == null ? null : c.name;
        d.categoryColor = c == null ? null : c.color;
        d.categoryIcon = c == null ? null : c.icon;
        d.notes = b.notes;
        d.active = b.active;
        d.nextDueDate = b.active ? Recurrence.nextOnOrAfter(b, Dates.today()) : null;
        d.matchStrictness = b.matchStrictness;
        return d;
    }

    /** A outra perna de uma transferência. */
    public static TransactionRec transferPeer(TransactionRec t) {
        if (t.transferId == null || t.transferId.isEmpty()) {
            return null;
        }
        TransferRec tr = Ctx.db().get(Entity.TRANSFER, t.transferId);
        if (tr == null) {
            return null;
        }
        String peerId = tr.fromTransactionId.equals(t.id) ? tr.toTransactionId : tr.fromTransactionId;
        return Ctx.db().get(Entity.TRANSACTION, peerId);
    }

    public static Transaction transactionDto(TransactionRec t) {
        return transactionDto(t, false, null);
    }

    /** {@code withInvoice}: pagamento de fatura leva o resumo da fatura agregada (lista e formulário usam). */
    public static Transaction transactionDto(TransactionRec t, boolean withInvoice, List<TransactionRec> txs) {
        LocalStore s = Ctx.db();
        AccountRec a = s.get(Entity.ACCOUNT, t.accountId);
        CategoryRec c = s.get(Entity.CATEGORY, t.categoryId);
        RecurringBillRec r = s.get(Entity.RECURRING_BILL, t.recurringBillId);
        TransferRec transfer = t.transferId != null && !t.transferId.isEmpty() ? s.get(Entity.TRANSFER, t.transferId) : null;
        TransactionRec peer = transfer != null ? transferPeer(t) : null;
        AccountRec peerAccount = peer != null ? s.get(Entity.ACCOUNT, peer.accountId) : null;
        AccountRec card = s.get(Entity.ACCOUNT, t.cardAccountId);
        String invoiceMonth = t.cardInvoiceMonth != null && !t.cardInvoiceMonth.isEmpty() ? Dates.monthOf(t.cardInvoiceMonth) : null;

        Transaction d = new Transaction();
        d.id = t.id;
        d.description = t.description;
        d.amount = t.amount;
        d.type = t.type;
        d.dueDate = t.dueDate;
        d.paid = t.paid;
        d.notes = t.notes;
        d.accountId = t.accountId;
        d.accountName = a == null ? "" : a.name;
        d.categoryId = c == null ? null : c.id;
        d.categoryName = c == null ? null : c.name;
        d.categoryColor = c == null ? null : c.color;
        d.categoryIcon = c == null ? null : c.icon;
        d.recurringBillId = r == null ? null : r.id;
        d.installmentNumber = t.installmentNumber;
        d.installmentTotal = r == null ? null : r.occurrences;
        d.scheduledDate = r != null ? t.scheduledDate : null;
        d.expectedAmount = r != null ? Long.valueOf(r.amount) : null;
        d.externalId = t.externalId;
        d.transferId = transfer == null ? null : transfer.id;
        d.transferPeerTransactionId = peer == null ? null : peer.id;
        d.transferPeerAccountId = peerAccount == null ? null : peerAccount.id;
        d.transferPeerAccountName = peerAccount == null ? null : peerAccount.name;
        d.merchant = t.merchant;
        d.receiptFile = t.receiptFile;
        d.receiptAccessKey = t.receiptAccessKey;
        d.importBatchId = t.importBatchId;
        if (t.items != null) {
            for (ItemRec i : t.items) {
                TransactionItem it = new TransactionItem();
                it.id = null;
                it.description = i.description;
                it.quantity = i.quantity;
                it.unitPrice = i.unitPrice;
                it.total = i.total;
                d.items.add(it);
            }
        }
        d.invoiceCardId = card == null ? null : card.id;
        d.invoiceCardName = card == null ? null : card.name;
        d.invoiceMonth = invoiceMonth;
        d.invoice = withInvoice && card != null && invoiceMonth != null
            ? CardsService.summarizeInvoice(card, invoiceMonth, txs) : null;
        d.receiptScanStatus = t.receiptScanStatus;
        d.receiptScanTotal = t.receiptScanTotal;
        d.receiptScanDate = t.receiptScanDate;
        return d;
    }
}
