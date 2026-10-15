package br.com.contaslt.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import br.com.contaslt.common.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class TransactionDtos {

    private TransactionDtos() {
    }

    public record TransactionRequest(
        @NotBlank(message = "Informe a descrição") @Size(max = 140) String description,
        @NotNull(message = "Informe o valor") @Positive(message = "O valor deve ser maior que zero") BigDecimal amount,
        @NotNull(message = "Informe o tipo") TransactionType type,
        @NotNull(message = "Informe a data") LocalDate dueDate,
        Boolean paid,
        @Size(max = 500) String notes,
        @NotNull(message = "Escolha a conta") Long accountId,
        Long categoryId) {
    }

    public record PaidRequest(@NotNull Boolean paid) {
    }

    public record TransactionDto(Long id, String description, BigDecimal amount, TransactionType type,
            LocalDate dueDate, boolean paid, String notes, Long accountId, String accountName,
            Long categoryId, String categoryName, String categoryColor, String categoryIcon,
            Long recurringBillId, Integer installmentNumber, Integer installmentTotal, String externalId) {
    }

    public record Summary(BigDecimal income, BigDecimal expense, BigDecimal balance,
            BigDecimal pendingIncome, BigDecimal pendingExpense, int count) {
    }

    public record TransactionPage(List<TransactionDto> items, Summary summary) {
    }
}
