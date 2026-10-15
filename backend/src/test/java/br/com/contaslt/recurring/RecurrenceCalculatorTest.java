package br.com.contaslt.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.contaslt.common.TransactionType;
import br.com.contaslt.recurring.RecurrenceCalculator.Occurrence;

class RecurrenceCalculatorTest {

    private static RecurringBill bill(Frequency f, LocalDate start, Integer occurrences, LocalDate end) {
        RecurringBill b = new RecurringBill();
        b.setDescription("Aluguel");
        b.setAmount(new BigDecimal("1500.00"));
        b.setType(TransactionType.EXPENSE);
        b.setFrequency(f);
        b.setStartDate(start);
        b.setOccurrences(occurrences);
        b.setEndDate(end);
        return b;
    }

    @Test
    void monthlyOnDay31ClampsToShorterMonths() {
        RecurringBill b = bill(Frequency.MONTHLY, LocalDate.of(2026, 1, 31), null, null);
        List<Occurrence> feb = RecurrenceCalculator.between(b, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));
        assertThat(feb).extracting(Occurrence::date).containsExactly(LocalDate.of(2026, 2, 28));
        List<Occurrence> mar = RecurrenceCalculator.between(b, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        assertThat(mar).extracting(Occurrence::date).containsExactly(LocalDate.of(2026, 3, 31));
    }

    @Test
    void installmentsStopAfterLastOne() {
        RecurringBill b = bill(Frequency.MONTHLY, LocalDate.of(2026, 1, 10), 3, null);
        List<Occurrence> all = RecurrenceCalculator.between(b, LocalDate.of(2025, 1, 1), LocalDate.of(2027, 12, 31));
        assertThat(all).extracting(Occurrence::number).containsExactly(1, 2, 3);
        assertThat(all.get(2).date()).isEqualTo(LocalDate.of(2026, 3, 10));
    }

    @Test
    void weeklyProducesSeveralPerMonth() {
        RecurringBill b = bill(Frequency.WEEKLY, LocalDate.of(2026, 10, 1), null, null);
        List<Occurrence> oct = RecurrenceCalculator.between(b, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(oct).hasSize(5);
    }

    @Test
    void endDateIsRespectedAndNextDateComputed() {
        RecurringBill b = bill(Frequency.MONTHLY, LocalDate.of(2026, 1, 5), null, LocalDate.of(2026, 3, 31));
        assertThat(RecurrenceCalculator.between(b, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30))).isEmpty();
        assertThat(RecurrenceCalculator.nextOnOrAfter(b, LocalDate.of(2026, 2, 6))).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(RecurrenceCalculator.nextOnOrAfter(b, LocalDate.of(2026, 4, 1))).isNull();
    }
}
