package br.com.contaslt.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;

import br.com.contaslt.common.TransactionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class RecurringBillDtos {

    private RecurringBillDtos() {
    }

    public record RecurringBillRequest(
        @NotBlank(message = "Informe a descrição") @Size(max = 140) String description,
        @NotNull(message = "Informe o valor") @Positive(message = "O valor deve ser maior que zero") BigDecimal amount,
        @NotNull(message = "Informe o tipo") TransactionType type,
        @NotNull(message = "Informe a frequência") Frequency frequency,
        @NotNull(message = "Informe a data do primeiro vencimento") LocalDate startDate,
        LocalDate endDate,
        @Min(value = 2, message = "Parcelamento precisa de pelo menos 2 parcelas") @Max(value = 600, message = "Máximo de 600 parcelas") Integer occurrences,
        @NotNull(message = "Escolha a conta") Long accountId,
        Long categoryId,
        @Size(max = 500) String notes,
        Boolean active) {
    }

    public record RecurringBillDto(Long id, String description, BigDecimal amount, TransactionType type,
            Frequency frequency, LocalDate startDate, LocalDate endDate, Integer occurrences,
            Long accountId, String accountName, Long categoryId, String categoryName, String categoryColor,
            String categoryIcon, String notes, boolean active, LocalDate nextDueDate) {
    }
}
