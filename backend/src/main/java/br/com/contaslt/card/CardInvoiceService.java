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
import br.com.contaslt.transaction.Transaction;
import br.com.contaslt.transaction.TransactionRepository;
import br.com.contaslt.user.User;

/**
 * Faturas de cartão de crédito. O cartão é uma conta; cada compra é um lançamento nele, na data da compra, com
 * itens e comprovante, mas compras não aparecem nas listas nem nos totais: o que conta como despesa é a fatura paga,
 * um lançamento comum na conta que pagou, ligado ao cartão e ao mês da fatura ({@code cardAccount} +
 * {@code cardInvoiceMonth}). As compras do período ficam agregadas dentro dele.
 */
@Service
public class CardInvoiceService {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private final TransactionRepository transactions;
    private final AccountService accounts;

    public CardInvoiceService(TransactionRepository transactions, AccountService accounts) {
        this.transactions = transactions;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public CardInvoiceDto invoice(User user, Long cardId, YearMonth month) {
        return summarize(requireCard(user.getId(), cardId), month);
    }

    public Account requireCard(Long userId, Long cardId) {
        Account card = accounts.require(userId, cardId);
        if (!card.isCreditCard()) {
            throw new BusinessException("A conta \"" + card.getName() + "\" não é um cartão de crédito");
        }
        return card;
    }

    public CardInvoiceDto summarize(Account card, YearMonth month) {
        InvoicePeriod p = InvoicePeriod.of(card, month);
        BigDecimal purchases = transactions.sumCardPurchases(card.getId(), p.start(), p.end());
        long count = transactions.countCardPurchases(card.getId(), p.start(), p.end());
        List<Transaction> payments = transactions.findByCardAccountIdAndCardInvoiceMonth(card.getId(), month.atDay(1));
        BigDecimal paid = payments.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal undetailed = paid.subtract(purchases).max(BigDecimal.ZERO);
        BigDecimal remaining = purchases.subtract(paid).max(BigDecimal.ZERO);
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
            purchases, count, paid, undetailed, remaining, status,
            payments.isEmpty() ? null : payments.get(0).getId());
    }

    /** "fatura de out/2026". */
    public static String label(YearMonth month) {
        String m = month.getMonth().getDisplayName(TextStyle.SHORT, PT_BR).replace(".", "");
        return "fatura de " + m + "/" + month.getYear();
    }

    /** Mês da fatura que um pagamento quita, quando não informado: o vencimento mais perto da data. */
    public static YearMonth inferInvoiceMonth(Account card, LocalDate paymentDate) {
        return InvoicePeriod.nearestDue(card, paymentDate).month();
    }
}
