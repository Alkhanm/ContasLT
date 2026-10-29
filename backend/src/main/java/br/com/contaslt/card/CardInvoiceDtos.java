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
        /** Pagamento registrado, mas menor que as compras. */
        PARTIAL,
        PAID
    }

    /**
     * Resumo de uma fatura. {@code purchases} são as compras registradas; {@code undetailed} é o ajuste
     * "compras não detalhadas" gerado quando o pagamento supera o que foi registrado; {@code total} soma os dois;
     * {@code paid} é o que já foi pago (transferências ligadas à fatura).
     */
    public record CardInvoiceDto(Long accountId, String accountName, YearMonth month, LocalDate start, LocalDate end,
            LocalDate dueDate, boolean configured, BigDecimal purchases, BigDecimal undetailed, BigDecimal total,
            BigDecimal paid, BigDecimal remaining, InvoiceStatus status) {
    }
}
