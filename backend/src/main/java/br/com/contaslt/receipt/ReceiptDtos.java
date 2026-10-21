package br.com.contaslt.receipt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ReceiptDtos {

    private ReceiptDtos() {
    }

    public record ScannedItem(String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal total) {
    }

    /** O que o modelo de visão conseguiu ler da imagem. */
    public record ReceiptScan(String merchant, LocalDate date, BigDecimal total, String paymentMethod,
            String accessKey, List<ScannedItem> items) {
    }

    /** Resposta do endpoint de leitura: dados lidos + arquivo guardado + sugestões do app. */
    public record ReceiptScanResponse(String receiptFile, String merchant, LocalDate date, BigDecimal total,
            BigDecimal itemsTotal, String paymentMethod, String accessKey, List<ScannedItem> items,
            Long suggestedCategoryId, String suggestedCategoryName,
            Long duplicateOfTransactionId, String duplicateOfDescription, LocalDate duplicateOfDate) {
    }
}
