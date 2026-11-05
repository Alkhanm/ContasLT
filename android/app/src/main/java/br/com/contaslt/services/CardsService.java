package br.com.contaslt.services;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import br.com.contaslt.data.schema.AccountRec;
import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.data.schema.TransactionRec;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.domain.Invoice;
import br.com.contaslt.dto.CardInvoice;

/**
 * Faturas de cartão (porte de frontend/src/services/cards.ts). Cada compra é um lançamento no cartão, na data da
 * compra, mas compras ficam fora das listas e dos totais: o que conta como despesa é a fatura paga, um lançamento comum
 * na conta que pagou, ligado ao cartão e ao mês da fatura ({@code cardAccountId} + {@code cardInvoiceMonth}).
 */
public final class CardsService {

    private CardsService() {
    }

    public static boolean isCreditCard(AccountRec a) {
        return a != null && "CREDIT_CARD".equals(a.type);
    }

    public static AccountRec requireCard(String cardId) {
        AccountRec card = Ctx.require(Ctx.db().get(Entity.ACCOUNT, cardId), "Conta não encontrada");
        if (!isCreditCard(card)) {
            throw new Ctx.BusinessException("A conta \"" + card.name + "\" não é um cartão de crédito");
        }
        return card;
    }

    /** Compras do cartão (sem pernas de transferência) num período: despesas somam, estornos (receitas) abatem. */
    private static long[] purchasesIn(String cardId, String from, String to, List<TransactionRec> txs) {
        long total = 0;
        long count = 0;
        for (TransactionRec t : txs) {
            if (!t.accountId.equals(cardId) || t.transferId != null || t.dueDate.compareTo(from) < 0
                || t.dueDate.compareTo(to) > 0) {
                continue;
            }
            total += "EXPENSE".equals(t.type) ? t.amount : -t.amount;
            count++;
        }
        return new long[] {total, count};
    }

    /** Pagamentos registrados de uma fatura: despesas (em qualquer conta) ligadas ao cartão e ao mês. */
    public static List<TransactionRec> invoicePayments(String cardId, String month, List<TransactionRec> txs) {
        String m = Dates.firstDay(month);
        List<TransactionRec> out = new ArrayList<>();
        for (TransactionRec t : txs) {
            if (cardId.equals(t.cardAccountId) && m.equals(t.cardInvoiceMonth)) {
                out.add(t);
            }
        }
        return out;
    }

    public static List<TransactionRec> invoicePayments(String cardId, String month) {
        return invoicePayments(cardId, month, Ctx.db().list(Entity.TRANSACTION));
    }

    public static CardInvoice summarizeInvoice(AccountRec card, String month) {
        return summarizeInvoice(card, month, Ctx.db().list(Entity.TRANSACTION));
    }

    public static CardInvoice summarizeInvoice(AccountRec card, String month, List<TransactionRec> txs) {
        if (txs == null) {
            txs = Ctx.db().list(Entity.TRANSACTION);
        }
        Invoice.Period p = Invoice.invoiceOf(card, month);
        long[] pc = purchasesIn(card.id, p.start(), p.end(), txs);
        long purchases = pc[0];
        List<TransactionRec> payments = invoicePayments(card.id, month, txs);
        long paid = 0;
        for (TransactionRec t : payments) {
            paid += t.amount;
        }
        long undetailed = Math.max(0, paid - purchases);
        long remaining = Math.max(0, purchases - paid);
        String status;
        if (paid > 0 && remaining == 0) {
            status = "PAID";
        } else if (paid > 0) {
            status = "PARTIAL";
        } else if (Dates.today().compareTo(p.end()) <= 0) {
            status = "OPEN";
        } else {
            status = "CLOSED";
        }
        CardInvoice inv = new CardInvoice();
        inv.accountId = card.id;
        inv.accountName = card.name;
        inv.month = month;
        inv.start = p.start();
        inv.end = p.end();
        inv.dueDate = p.dueDate();
        inv.configured = p.configured();
        inv.purchases = purchases;
        inv.count = (int) pc[1];
        inv.paid = paid;
        inv.undetailed = undetailed;
        inv.remaining = remaining;
        inv.status = status;
        inv.paymentTransactionId = payments.isEmpty() ? null : payments.get(0).id;
        return inv;
    }

    /** Saldo devedor do cartão: compras (líquidas) fora dos períodos das faturas que já têm pagamento registrado. */
    public static long openDebt(AccountRec card) {
        return openDebt(card, Ctx.db().list(Entity.TRANSACTION));
    }

    public static long openDebt(AccountRec card, List<TransactionRec> txs) {
        Set<String> months = new LinkedHashSet<>();
        for (TransactionRec t : txs) {
            if (card.id.equals(t.cardAccountId) && t.cardInvoiceMonth != null && !t.cardInvoiceMonth.isEmpty()) {
                months.add(Dates.monthOf(t.cardInvoiceMonth));
            }
        }
        List<Invoice.Period> paidPeriods = new ArrayList<>();
        for (String m : months) {
            paidPeriods.add(Invoice.invoiceOf(card, m));
        }
        long debt = 0;
        outer:
        for (TransactionRec t : txs) {
            if (!t.accountId.equals(card.id) || t.transferId != null) {
                continue;
            }
            for (Invoice.Period p : paidPeriods) {
                if (t.dueDate.compareTo(p.start()) >= 0 && t.dueDate.compareTo(p.end()) <= 0) {
                    continue outer;
                }
            }
            debt += "EXPENSE".equals(t.type) ? t.amount : -t.amount;
        }
        return debt;
    }
}
