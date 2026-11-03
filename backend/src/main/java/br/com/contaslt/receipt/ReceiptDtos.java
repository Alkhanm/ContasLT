package br.com.contaslt.receipt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;

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
            BigDecimal recurringMatchAmount, Long recurringMatchCategoryId, Long recurringMatchAccountId,
            /**
             * Fila do worker: {@code status} DONE (dados acima válidos), PENDING (só {@code receiptFile}; consulte
             * {@code GET /api/receipts/jobs/{jobId}}) ou FAILED ({@code error}). {@code workerOnline} diz se vale esperar.
             */
            Long jobId, String status, boolean workerOnline, String error) {

        public static ReceiptScanResponse pending(Long jobId, String receiptFile, boolean workerOnline) {
            return new ReceiptScanResponse(receiptFile, "", null, true, null, BigDecimal.ZERO, "", null, List.of(),
                null, null, null, null, null, null, null, null, null, null, null, jobId, "PENDING", workerOnline, null);
        }

        public static ReceiptScanResponse failed(Long jobId, String receiptFile, boolean workerOnline, String error) {
            return new ReceiptScanResponse(receiptFile, "", null, true, null, BigDecimal.ZERO, "", null, List.of(),
                null, null, null, null, null, null, null, null, null, null, null, jobId, "FAILED", workerOnline, error);
        }
    }

    /** Tarefa reservada para o worker. */
    public record ClaimedJob(Long id, String receiptFile) {
    }

    /** JSON cru devolvido pelo modelo de visão no worker. */
    public record WorkerResult(@NotBlank String content) {
    }

    /** {@code retryable}: erro do ambiente (Ollama fora do ar), volta para a fila sem contar tentativa. */
    public record WorkerFailure(String error, boolean retryable) {
    }
}
