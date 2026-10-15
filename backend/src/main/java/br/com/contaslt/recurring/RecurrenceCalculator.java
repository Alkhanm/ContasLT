package br.com.contaslt.recurring;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Calcula as datas de vencimento de uma conta recorrente. */
public final class RecurrenceCalculator {

    private static final int MAX_ITERATIONS = 5000;

    private RecurrenceCalculator() {
    }

    /** Uma ocorrência: data e número sequencial (1 = primeira). */
    public record Occurrence(LocalDate date, int number) {
    }

    public static List<Occurrence> between(RecurringBill bill, LocalDate from, LocalDate to) {
        List<Occurrence> result = new ArrayList<>();
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (bill.getOccurrences() != null && i >= bill.getOccurrences()) {
                break;
            }
            LocalDate date = bill.getFrequency().nth(bill.getStartDate(), i);
            if (date.isAfter(to) || (bill.getEndDate() != null && date.isAfter(bill.getEndDate()))) {
                break;
            }
            if (!date.isBefore(from)) {
                result.add(new Occurrence(date, i + 1));
            }
        }
        return result;
    }

    /** Próxima data igual ou posterior a {@code reference}, ou null se a recorrência já terminou. */
    public static LocalDate nextOnOrAfter(RecurringBill bill, LocalDate reference) {
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (bill.getOccurrences() != null && i >= bill.getOccurrences()) {
                return null;
            }
            LocalDate date = bill.getFrequency().nth(bill.getStartDate(), i);
            if (bill.getEndDate() != null && date.isAfter(bill.getEndDate())) {
                return null;
            }
            if (!date.isBefore(reference)) {
                return date;
            }
        }
        return null;
    }
}
