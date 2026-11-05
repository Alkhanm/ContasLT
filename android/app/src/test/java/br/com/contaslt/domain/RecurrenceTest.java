package br.com.contaslt.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import br.com.contaslt.data.schema.RecurringBillRec;

/** Porte de frontend/src/domain/recurrence.test.ts (RecurrenceCalculatorTest). */
public class RecurrenceTest {

    private static RecurringBillRec bill(String frequency, String startDate, Integer occurrences, String endDate) {
        RecurringBillRec b = new RecurringBillRec();
        b.frequency = frequency;
        b.startDate = startDate;
        b.occurrences = occurrences;
        b.endDate = endDate;
        b.startNumber = 1;
        return b;
    }

    private static List<String> dates(List<Recurrence.Occurrence> occ) {
        List<String> out = new ArrayList<>();
        for (Recurrence.Occurrence o : occ) {
            out.add(o.date());
        }
        return out;
    }

    private static List<Integer> numbers(List<Recurrence.Occurrence> occ) {
        List<Integer> out = new ArrayList<>();
        for (Recurrence.Occurrence o : occ) {
            out.add(o.number());
        }
        return out;
    }

    @Test
    public void mensalNoDia31FicaNoUltimoDia() {
        RecurringBillRec b = bill("MONTHLY", "2026-01-31", null, null);
        assertEquals(Arrays.asList("2026-02-28"), dates(Recurrence.between(b, "2026-02-01", "2026-02-28")));
        assertEquals(Arrays.asList("2026-03-31"), dates(Recurrence.between(b, "2026-03-01", "2026-03-31")));
    }

    @Test
    public void parcelamentoParaDepoisDaUltimaParcela() {
        RecurringBillRec b = bill("MONTHLY", "2026-01-10", 3, null);
        List<Recurrence.Occurrence> all = Recurrence.between(b, "2025-01-01", "2027-12-31");
        assertEquals(Arrays.asList(1, 2, 3), numbers(all));
        assertEquals("2026-03-10", all.get(2).date());
    }

    @Test
    public void semanalGeraVariosVencimentos() {
        RecurringBillRec b = bill("WEEKLY", "2026-10-01", null, null);
        assertEquals(5, Recurrence.between(b, "2026-10-01", "2026-10-31").size());
    }

    @Test
    public void respeitaDataFinalEProximoVencimento() {
        RecurringBillRec b = bill("MONTHLY", "2026-01-05", null, "2026-03-31");
        assertTrue(Recurrence.between(b, "2026-04-01", "2026-04-30").isEmpty());
        assertEquals("2026-03-05", Recurrence.nextOnOrAfter(b, "2026-02-06"));
        assertNull(Recurrence.nextOnOrAfter(b, "2026-04-01"));
    }

    @Test
    public void frequenciasSemDeslizarODia() {
        assertEquals("2026-01-29", Recurrence.nth("BIWEEKLY", "2026-01-01", 2));
        assertEquals("2026-03-31", Recurrence.nth("BIMONTHLY", "2026-01-31", 1));
        assertEquals("2026-02-28", Recurrence.nth("QUARTERLY", "2025-11-30", 1));
        assertEquals("2027-02-28", Recurrence.nth("SEMIANNUAL", "2026-08-31", 1));
        assertEquals("2025-02-28", Recurrence.nth("YEARLY", "2024-02-29", 1));
        assertEquals("2028-02-29", Recurrence.nth("YEARLY", "2024-02-29", 4));
        assertEquals("2026-03-31", Recurrence.nth("MONTHLY", "2026-01-31", 2));
    }

    @Test
    public void numberOfConsideraNumeroInicial() {
        RecurringBillRec b = bill("MONTHLY", "2034-03-01", 6, null);
        b.startNumber = 3;
        assertEquals(Integer.valueOf(4), Recurrence.numberOf(b, "2034-04-01"));
        assertNull(Recurrence.numberOf(b, "2034-04-02"));
        assertEquals(Arrays.asList(3, 4, 5, 6), numbers(Recurrence.between(b, "2034-01-01", "2034-12-31")));
    }
}
