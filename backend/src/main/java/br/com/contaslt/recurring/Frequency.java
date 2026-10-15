package br.com.contaslt.recurring;

import java.time.LocalDate;

public enum Frequency {
    WEEKLY, BIWEEKLY, MONTHLY, BIMONTHLY, QUARTERLY, SEMIANNUAL, YEARLY;

    /** Data da ocorrência de índice {@code n} (0 = data inicial), sem drift de dia do mês. */
    public LocalDate nth(LocalDate start, int n) {
        return switch (this) {
            case WEEKLY -> start.plusWeeks(n);
            case BIWEEKLY -> start.plusWeeks(2L * n);
            case MONTHLY -> start.plusMonths(n);
            case BIMONTHLY -> start.plusMonths(2L * n);
            case QUARTERLY -> start.plusMonths(3L * n);
            case SEMIANNUAL -> start.plusMonths(6L * n);
            case YEARLY -> start.plusYears(n);
        };
    }
}
