package br.com.contaslt.domain;

import java.util.ArrayList;
import java.util.List;

import br.com.contaslt.data.schema.RecurringBillRec;

/** Calcula as datas de vencimento de uma conta recorrente (porte de frontend/src/domain/recurrence.ts). */
public final class Recurrence {

    private static final int MAX_ITERATIONS = 5000;

    private Recurrence() {
    }

    /** Uma ocorrência: data e número sequencial (1 = primeira). */
    public record Occurrence(String date, int number) {
    }

    /** Data da ocorrência de índice n (0 = data inicial), sem drift de dia do mês. */
    public static String nth(String frequency, String start, int n) {
        return switch (frequency) {
            case "WEEKLY" -> Dates.plusWeeks(start, n);
            case "BIWEEKLY" -> Dates.plusWeeks(start, 2L * n);
            case "MONTHLY" -> Dates.plusMonths(start, n);
            case "BIMONTHLY" -> Dates.plusMonths(start, 2L * n);
            case "QUARTERLY" -> Dates.plusMonths(start, 3L * n);
            case "SEMIANNUAL" -> Dates.plusMonths(start, 6L * n);
            case "YEARLY" -> Dates.plusYears(start, n);
            default -> throw new IllegalArgumentException("Frequência inválida: " + frequency);
        };
    }

    public static List<Occurrence> between(RecurringBillRec bill, String from, String to) {
        List<Occurrence> result = new ArrayList<>();
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            int number = bill.startNumber + i;
            if (bill.occurrences != null && number > bill.occurrences) {
                break;
            }
            String date = nth(bill.frequency, bill.startDate, i);
            if (date.compareTo(to) > 0 || (bill.endDate != null && date.compareTo(bill.endDate) > 0)) {
                break;
            }
            if (date.compareTo(from) >= 0) {
                result.add(new Occurrence(date, number));
            }
        }
        return result;
    }

    /** Número da ocorrência prevista para a data, ou null se a regra não cai nela. */
    public static Integer numberOf(RecurringBillRec bill, String date) {
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            String d = nth(bill.frequency, bill.startDate, i);
            if (d.compareTo(date) > 0) {
                return null;
            }
            if (d.equals(date)) {
                return bill.startNumber + i;
            }
        }
        return null;
    }

    /** Próxima data igual ou posterior à referência, ou null se a recorrência já terminou. */
    public static String nextOnOrAfter(RecurringBillRec bill, String reference) {
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (bill.occurrences != null && bill.startNumber + i > bill.occurrences) {
                return null;
            }
            String date = nth(bill.frequency, bill.startDate, i);
            if (bill.endDate != null && date.compareTo(bill.endDate) > 0) {
                return null;
            }
            if (date.compareTo(reference) >= 0) {
                return date;
            }
        }
        return null;
    }
}
