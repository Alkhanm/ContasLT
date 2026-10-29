package br.com.contaslt.card;

import java.time.LocalDate;
import java.time.YearMonth;

import br.com.contaslt.account.Account;

/**
 * Recorte de uma fatura de cartão: as compras de {@code start} a {@code end} (dia do fechamento) vencem em
 * {@code dueDate}. A fatura é identificada pelo mês do vencimento ({@code month}), que é como se fala dela:
 * "fatura de outubro" é a que vence em outubro.
 *
 * <p>Sem fechamento/vencimento configurados no cartão, a fatura vira o próprio mês civil, vencendo no último dia.</p>
 */
public record InvoicePeriod(YearMonth month, LocalDate start, LocalDate end, LocalDate dueDate, boolean configured) {

    /** Fatura que vence no mês informado. */
    public static InvoicePeriod of(Account card, YearMonth month) {
        if (card.getClosingDay() == null || card.getDueDay() == null) {
            return new InvoicePeriod(month, month.atDay(1), month.atEndOfMonth(), month.atEndOfMonth(), false);
        }
        LocalDate end = closingFor(card, month);
        LocalDate start = closingFor(card, month.minusMonths(1)).plusDays(1);
        return new InvoicePeriod(month, start, end, clamp(month, card.getDueDay()), true);
    }

    /** Fatura em que cai uma compra feita na data informada. */
    public static InvoicePeriod containing(Account card, LocalDate purchaseDate) {
        if (card.getClosingDay() == null || card.getDueDay() == null) {
            return of(card, YearMonth.from(purchaseDate));
        }
        YearMonth closingMonth = purchaseDate.getDayOfMonth() <= card.getClosingDay()
            ? YearMonth.from(purchaseDate) : YearMonth.from(purchaseDate).plusMonths(1);
        YearMonth due = card.getDueDay() > card.getClosingDay() ? closingMonth : closingMonth.plusMonths(1);
        return of(card, due);
    }

    /** Fatura cujo vencimento está mais perto da data de um pagamento. */
    public static InvoicePeriod nearestDue(Account card, LocalDate paymentDate) {
        YearMonth ym = YearMonth.from(paymentDate);
        InvoicePeriod best = null;
        long bestDistance = Long.MAX_VALUE;
        for (YearMonth m = ym.minusMonths(1); !m.isAfter(ym.plusMonths(1)); m = m.plusMonths(1)) {
            InvoicePeriod p = of(card, m);
            long distance = Math.abs(p.dueDate().toEpochDay() - paymentDate.toEpochDay());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = p;
            }
        }
        return best;
    }

    /** Data de fechamento da fatura que vence no mês informado. */
    private static LocalDate closingFor(Account card, YearMonth dueMonth) {
        YearMonth closingMonth = card.getDueDay() > card.getClosingDay() ? dueMonth : dueMonth.minusMonths(1);
        return clamp(closingMonth, card.getClosingDay());
    }

    private static LocalDate clamp(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }
}
