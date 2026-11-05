package br.com.contaslt.domain;

import br.com.contaslt.data.schema.AccountRec;

/**
 * Recorte de uma fatura de cartão (porte de frontend/src/domain/invoice.ts): as compras de {@code start} a {@code end}
 * (dia do fechamento) vencem em {@code dueDate}. A fatura é identificada pelo mês do vencimento. Sem
 * fechamento/vencimento no cartão, vale o mês civil, vencendo no último dia.
 */
public final class Invoice {

    public record Period(String month, String start, String end, String dueDate, boolean configured) {
    }

    private static final String[] MONTHS = {
        "jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez",
    };

    private Invoice() {
    }

    public static Period invoiceOf(AccountRec card, String month) {
        if (card.closingDay == null || card.dueDay == null) {
            return new Period(month, Dates.firstDay(month), Dates.endOfMonth(month), Dates.endOfMonth(month), false);
        }
        String end = closingFor(card, month);
        String start = Dates.plusDays(closingFor(card, Dates.plusMonthsYm(month, -1)), 1);
        return new Period(month, start, end, Dates.atDay(month, card.dueDay), true);
    }

    /** Fatura em que cai uma compra feita na data. */
    public static Period invoiceContaining(AccountRec card, String purchaseDate) {
        if (card.closingDay == null || card.dueDay == null) {
            return invoiceOf(card, Dates.monthOf(purchaseDate));
        }
        int day = Integer.parseInt(purchaseDate.substring(8, 10));
        String closingMonth = day <= card.closingDay ? Dates.monthOf(purchaseDate)
            : Dates.plusMonthsYm(Dates.monthOf(purchaseDate), 1);
        String due = card.dueDay > card.closingDay ? closingMonth : Dates.plusMonthsYm(closingMonth, 1);
        return invoiceOf(card, due);
    }

    /** Fatura cujo vencimento está mais perto da data de um pagamento. */
    public static Period nearestDue(AccountRec card, String paymentDate) {
        String ym = Dates.monthOf(paymentDate);
        Period best = null;
        long bestDistance = Long.MAX_VALUE;
        for (int i = -1; i <= 1; i++) {
            Period p = invoiceOf(card, Dates.plusMonthsYm(ym, i));
            long distance = Math.abs(Dates.toEpochDay(p.dueDate()) - Dates.toEpochDay(paymentDate));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = p;
            }
        }
        return best;
    }

    /** Mês da fatura que um pagamento quita, quando não informado: o vencimento mais perto da data. */
    public static String inferInvoiceMonth(AccountRec card, String paymentDate) {
        return nearestDue(card, paymentDate).month();
    }

    private static String closingFor(AccountRec card, String dueMonth) {
        String closingMonth = card.dueDay > card.closingDay ? dueMonth : Dates.plusMonthsYm(dueMonth, -1);
        return Dates.atDay(closingMonth, card.closingDay);
    }

    /** "fatura de out/2026". */
    public static String invoiceLabel(String month) {
        return "fatura de " + MONTHS[Integer.parseInt(month.substring(5, 7)) - 1] + "/" + month.substring(0, 4);
    }
}
