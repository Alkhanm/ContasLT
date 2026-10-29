package br.com.contaslt.card;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.contaslt.account.Account;
import br.com.contaslt.account.AccountService;
import br.com.contaslt.card.CardInvoiceDtos.CardInvoiceDto;
import br.com.contaslt.card.CardInvoiceDtos.InvoiceStatus;
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.common.TransactionType;
import br.com.contaslt.transaction.Transaction;
import br.com.contaslt.transaction.TransactionRepository;
import br.com.contaslt.transfer.Transfer;
import br.com.contaslt.transfer.TransferRepository;
import br.com.contaslt.user.User;

/**
 * Faturas de cartão de crédito. O cartão é uma conta; cada compra é uma despesa nele, na data da compra; a fatura é
 * só o recorte das compras entre dois fechamentos, e pagá-la é uma transferência conta → cartão marcada com o mês da
 * fatura. Como nem toda compra é registrada (nem todo comprovante é lido), o que foi pago além das compras
 * registradas vira uma despesa "compras não detalhadas" no cartão, recalculada a cada mudança.
 */
@Service
public class CardInvoiceService {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private final TransactionRepository transactions;
    private final TransferRepository transfers;
    private final AccountService accounts;

    public CardInvoiceService(TransactionRepository transactions, TransferRepository transfers, AccountService accounts) {
        this.transactions = transactions;
        this.transfers = transfers;
        this.accounts = accounts;
    }

    @Transactional
    public CardInvoiceDto invoice(User user, Long cardId, YearMonth month) {
        Account card = requireCard(user.getId(), cardId);
        refresh(user, card, month);
        return summarize(card, month);
    }

    public Account requireCard(Long userId, Long cardId) {
        Account card = accounts.require(userId, cardId);
        if (!card.isCreditCard()) {
            throw new BusinessException("A conta \"" + card.getName() + "\" não é um cartão de crédito");
        }
        return card;
    }

    /** Recalcula o ajuste "compras não detalhadas" da fatura. Pode ser chamado à vontade: é idempotente. */
    @Transactional
    public void refresh(User user, Account card, YearMonth month) {
        if (!card.isCreditCard()) {
            return;
        }
        InvoicePeriod p = InvoicePeriod.of(card, month);
        BigDecimal purchases = transactions.sumCardPurchases(card.getId(), p.start(), p.end());
        BigDecimal paid = paidTotal(card, month);
        BigDecimal gap = paid.subtract(purchases);
        List<Transaction> existing = transactions.findByAccountIdAndInvoiceGapTrueAndDueDateBetween(card.getId(),
            p.start(), p.end());
        if (gap.signum() <= 0) {
            transactions.deleteAll(existing);
            return;
        }
        Transaction t = existing.isEmpty() ? new Transaction() : existing.get(0);
        if (existing.size() > 1) {
            transactions.deleteAll(existing.subList(1, existing.size()));
        }
        if (t.getId() == null) {
            t.setUser(user);
            t.setAccount(card);
            t.setType(TransactionType.EXPENSE);
            t.setInvoiceGap(true);
            t.setDescription("Compras não detalhadas · " + label(month));
        }
        t.setAmount(gap);
        t.setDueDate(p.end());
        t.setPaid(true);
        transactions.save(t);
    }

    /** Recalcula a fatura em que cai um lançamento do cartão (compra, estorno) depois de ele mudar. */
    @Transactional
    public void refreshFor(User user, Account account, LocalDate date) {
        if (account != null && account.isCreditCard() && date != null) {
            refresh(user, account, InvoicePeriod.containing(account, date).month());
        }
    }

    public CardInvoiceDto summarize(Account card, YearMonth month) {
        InvoicePeriod p = InvoicePeriod.of(card, month);
        BigDecimal purchases = transactions.sumCardPurchases(card.getId(), p.start(), p.end());
        BigDecimal undetailed = transactions.findByAccountIdAndInvoiceGapTrueAndDueDateBetween(card.getId(), p.start(), p.end())
            .stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = purchases.add(undetailed);
        BigDecimal paid = paidTotal(card, month);
        BigDecimal remaining = total.subtract(paid).max(BigDecimal.ZERO);
        InvoiceStatus status;
        if (paid.signum() > 0 && remaining.signum() == 0) {
            status = InvoiceStatus.PAID;
        } else if (paid.signum() > 0) {
            status = InvoiceStatus.PARTIAL;
        } else if (!LocalDate.now().isAfter(p.end())) {
            status = InvoiceStatus.OPEN;
        } else {
            status = InvoiceStatus.CLOSED;
        }
        return new CardInvoiceDto(card.getId(), card.getName(), month, p.start(), p.end(), p.dueDate(), p.configured(),
            purchases, undetailed, total, paid, remaining, status);
    }

    private BigDecimal paidTotal(Account card, YearMonth month) {
        return transfers.findInvoicePayments(card.getId(), month.atDay(1)).stream()
            .map(tr -> tr.getToTransaction().getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** "fatura de out/2026". */
    public static String label(YearMonth month) {
        String m = month.getMonth().getDisplayName(TextStyle.SHORT, PT_BR).replace(".", "");
        return "fatura de " + m + "/" + month.getYear();
    }

    /** Mês da fatura que um pagamento ao cartão quita, quando não informado: o vencimento mais perto da data. */
    public static YearMonth inferInvoiceMonth(Account card, LocalDate paymentDate) {
        return InvoicePeriod.nearestDue(card, paymentDate).month();
    }

    /** Guarda do ajuste automático: ele não é editado nem excluído à mão. */
    public static void rejectManualChange(Transaction t) {
        if (t.isInvoiceGap()) {
            throw new BusinessException("\"Compras não detalhadas\" é calculado pela fatura: registre as compras do "
                + "cartão (ou ajuste o pagamento) e ele muda sozinho. Só a categoria e as observações podem ser editadas.");
        }
    }

    public static boolean isLinkedPayment(Transfer tr) {
        return tr.getInvoiceMonth() != null;
    }
}
