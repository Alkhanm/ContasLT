package br.com.contaslt.receipt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ReceiptDtos {

    private ReceiptDtos() {
    }

    public record ScannedItem(String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal total) {
    }

    /**
     * O que o modelo de visão conseguiu ler da imagem. {@code issueDate} é a emissão/compra; {@code dueDate} só existe
     * em faturas e boletos; {@code paid} diz se o documento comprova pagamento (cupom, recibo) ou é uma conta a pagar.
     */
    public record ReceiptScan(String merchant, LocalDate issueDate, LocalDate dueDate, boolean paid, BigDecimal total,
            String paymentMethod, String accessKey, List<ScannedItem> items) {
    }

    /**
     * Resposta do endpoint de leitura: dados lidos + arquivo guardado + sugestões do app.
     * {@code date} é a data para o lançamento: vencimento, se houver; senão emissão; senão o dia da leitura.
     */
    public record ReceiptScanResponse(String receiptFile, String merchant, LocalDate date, boolean paid, BigDecimal total,
            BigDecimal itemsTotal, String paymentMethod, String accessKey, List<ScannedItem> items,
            Long suggestedCategoryId, String suggestedCategoryName,
            Long duplicateOfTransactionId, String duplicateOfDescription, LocalDate duplicateOfDate,
            /** Vencimento pendente de conta recorrente que este comprovante parece pagar. */
            Long recurringMatchTransactionId, String recurringMatchDescription, LocalDate recurringMatchDate,
            BigDecimal recurringMatchAmount, Long recurringMatchCategoryId, Long recurringMatchAccountId) {
    }
}
