package br.com.contaslt.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import br.com.contaslt.common.TransactionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class ImportDtos {

    private ImportDtos() {
    }

    /**
     * Linha lida do arquivo. Quando {@code matchTransactionId} vem preenchido, já existe um lançamento manual
     * (ex.: criado a partir de um comprovante) que parece ser esta mesma compra; o usuário decide se vincula.
     */
    public record PreviewRow(int index, LocalDate date, String description, BigDecimal amount, TransactionType type,
            String externalId, boolean duplicate, Long suggestedCategoryId, String suggestedCategoryName,
            String suggestedCategoryColor, String suggestedCategoryIcon,
            Long matchTransactionId, String matchDescription, LocalDate matchDate, String matchAccountName,
            int matchItemCount, boolean matchHasReceipt, Long matchCategoryId, String matchCategoryName) {
    }

    public record ImportPreview(ImportSource source, String filename, Long accountId, int total, int duplicates,
            List<PreviewRow> rows) {
    }

    public record ConfirmRow(
        @NotNull LocalDate date,
        @NotBlank @Size(max = 140) String description,
        @NotNull @Positive BigDecimal amount,
        @NotNull TransactionType type,
        Long categoryId,
        @Size(max = 120) String externalId,
        Boolean paid,
        Boolean learnRule,
        /** Em vez de criar um lançamento, vincula esta linha ao lançamento existente informado. */
        Long linkTransactionId) {
    }

    public record ImportConfirmRequest(
        @NotNull(message = "Escolha a conta") Long accountId,
        @NotNull ImportSource source,
        @Size(max = 255) String filename,
        @NotEmpty(message = "Selecione ao menos um lançamento") @Valid List<ConfirmRow> rows) {
    }

    public record ImportResult(Long batchId, int imported, int skipped, int linked) {
    }

    public record ImportBatchDto(Long id, ImportSource source, String filename, Long accountId, String accountName,
            int importedCount, LocalDateTime createdAt) {
    }

    public record CategoryRuleRequest(
        @NotBlank(message = "Informe a palavra-chave") @Size(max = 120) String keyword,
        @NotNull(message = "Escolha a categoria") Long categoryId) {
    }

    public record CategoryRuleDto(Long id, String keyword, Long categoryId, String categoryName, String categoryColor,
            String categoryIcon) {
    }
}
