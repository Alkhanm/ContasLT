package br.com.contaslt.receipt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
     * Resposta da leitura: o servidor só guarda a foto e lê; o app calcula as sugestões (categoria, duplicata,
     * vencimento recorrente). {@code status} DONE ({@code scan} preenchido), PENDING (na fila do worker; consulte
     * {@code GET /api/receipts/jobs/{jobId}}) ou FAILED ({@code error}). {@code qrAccessKey} é a chave lida do QR code,
     * mais confiável que a lida pelo modelo. {@code workerOnline} diz se vale esperar.
     */
    public record ReceiptRead(String receiptFile, Long jobId, String status, boolean workerOnline, String error,
            String qrAccessKey, ReceiptScan scan) {
    }

    /** Leitura terminada de uma foto já salva num lançamento ({@code transactionId} é o UUID do registro do app). */
    public record JobResult(Long jobId, UUID transactionId, String receiptFile, String status, String error,
            String qrAccessKey, ReceiptScan scan) {
    }

    /** A foto {@code receiptFile} foi salva no lançamento {@code transactionId}. */
    public record AttachRequest(@NotBlank String receiptFile, @NotNull UUID transactionId) {
    }

    public record Uploaded(String receiptFile) {
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
