package br.com.contaslt.card;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

public final class CardInvoiceDtos {

    private CardInvoiceDtos() {
    }

    public enum InvoiceStatus {
        /** Ainda recebendo compras (hoje não passou do fechamento). */
        OPEN,
        /** Fechada e sem pagamento registrado. */
        CLOSED,
        /** Pagamento registrado, mas menor que as compras registradas. */
        PARTIAL,
        PAID
    }

    /**
     * Resumo de uma fatura: o recorte das compras do cartão entre dois fechamentos. {@code purchases}/{@code count}
     * são as compras registradas no período; {@code paid} é o que foi pago (despesas ligadas à fatura);
     * {@code undetailed} é o que foi pago além das compras registradas (compras sem comprovante lido).
     */
    public record CardInvoiceDto(Long accountId, String accountName, YearMonth month, LocalDate start, LocalDate end,
            LocalDate dueDate, boolean configured, BigDecimal purchases, long count, BigDecimal paid,
            BigDecimal undetailed, BigDecimal remaining, InvoiceStatus status, Long paymentTransactionId) {
    }
}
