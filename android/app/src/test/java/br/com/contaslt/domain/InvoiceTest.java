package br.com.contaslt.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import br.com.contaslt.data.schema.AccountRec;

/** Porte de frontend/src/domain/invoice.test.ts. */
public class InvoiceTest {

    private static AccountRec card(Integer closingDay, Integer dueDay) {
        AccountRec a = new AccountRec();
        a.type = "CREDIT_CARD";
        a.closingDay = closingDay;
        a.dueDay = dueDay;
        return a;
    }

    // Fecha dia 25, vence dia 5 do mês seguinte (o cartão do CardInvoiceTest).
    private final AccountRec nubank = card(25, 5);

    @Test
    public void faturaDeOutubro() {
        assertEquals(new Invoice.Period("2034-10", "2034-08-26", "2034-09-25", "2034-10-05", true),
            Invoice.invoiceOf(nubank, "2034-10"));
    }

    @Test
    public void compraNoDiaDoFechamento() {
        assertEquals("2034-10", Invoice.invoiceContaining(nubank, "2034-09-25").month());
        assertEquals("2034-11", Invoice.invoiceContaining(nubank, "2034-09-26").month());
        assertEquals("2034-10", Invoice.invoiceContaining(nubank, "2034-09-10").month());
    }

    @Test
    public void vencimentoDepoisDoFechamentoNoMesmoMes() {
        AccountRec c = card(3, 10);
        Invoice.Period p = Invoice.invoiceOf(c, "2034-10");
        assertEquals("2034-09-04", p.start());
        assertEquals("2034-10-03", p.end());
        assertEquals("2034-10-10", p.dueDate());
        assertEquals("2034-10", Invoice.invoiceContaining(c, "2034-10-03").month());
        assertEquals("2034-11", Invoice.invoiceContaining(c, "2034-10-04").month());
    }

    @Test
    public void semFechamentoValeOMesCivil() {
        AccountRec c = card(null, null);
        assertEquals(new Invoice.Period("2032-02", "2032-02-01", "2032-02-29", "2032-02-29", false),
            Invoice.invoiceOf(c, "2032-02"));
        assertEquals("2032-02", Invoice.invoiceContaining(c, "2032-02-15").month());
    }

    @Test
    public void pagamentoQuitaFaturaMaisProxima() {
        assertEquals("2034-10", Invoice.inferInvoiceMonth(nubank, "2034-10-05"));
        assertEquals("2034-10", Invoice.inferInvoiceMonth(nubank, "2034-10-01"));
        assertEquals("2034-11", Invoice.inferInvoiceMonth(nubank, "2034-10-28"));
        assertEquals("2034-10-05", Invoice.nearestDue(nubank, "2034-09-30").dueDate());
    }

    @Test
    public void rotulo() {
        assertEquals("fatura de out/2026", Invoice.invoiceLabel("2026-10"));
    }
}
