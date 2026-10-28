package br.com.contaslt.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import br.com.contaslt.common.TransactionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class TransactionDtos {

    private TransactionDtos() {
    }

    public record ItemRequest(
        @NotBlank(message = "Informe a descrição do item") @Size(max = 140) String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        @NotNull(message = "Informe o valor do item") BigDecimal total) {
    }

    public record ItemDto(Long id, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal total) {
    }

    public record TransactionRequest(
        @NotBlank(message = "Informe a descrição") @Size(max = 140) String description,
        @NotNull(message = "Informe o valor") @Positive(message = "O valor deve ser maior que zero") BigDecimal amount,
        @NotNull(message = "Informe o tipo") TransactionType type,
        @NotNull(message = "Informe a data") LocalDate dueDate,
        Boolean paid,
        @Size(max = 500) String notes,
        @NotNull(message = "Escolha a conta") Long accountId,
        Long categoryId,
        @Size(max = 120) String merchant,
        @Size(max = 80) String receiptFile,
        @Size(max = 44) String receiptAccessKey,
        @Valid @Size(max = 200) List<ItemRequest> items) {
    }

    public record PaidRequest(@NotNull Boolean paid) {
    }

    public record TransactionDto(Long id, String description, BigDecimal amount, TransactionType type,
            LocalDate dueDate, boolean paid, String notes, Long accountId, String accountName,
            Long categoryId, String categoryName, String categoryColor, String categoryIcon,
            Long recurringBillId, Integer installmentNumber, Integer installmentTotal, String externalId,
            Long transferId, Long transferPeerTransactionId, Long transferPeerAccountId, String transferPeerAccountName,
            String merchant, String receiptFile, String receiptAccessKey, Long importBatchId, List<ItemDto> items) {
    }

    /**
     * Totais da listagem. Receitas e despesas ignoram pernas de transferência; transferOut/transferIn
     * somam, separadamente, o que saiu e o que entrou por movimentação interna entre as contas listadas.
     */
    public record Summary(BigDecimal income, BigDecimal expense, BigDecimal balance,
            BigDecimal pendingIncome, BigDecimal pendingExpense, BigDecimal transferOut, BigDecimal transferIn,
            int count) {
    }

    /** Saldo das contas (todas ou a filtrada) ao fim do dia, contando só o que está pago, como o saldo da conta. */
    public record DayBalance(LocalDate date, BigDecimal balance) {
    }

    public record TransactionPage(List<TransactionDto> items, Summary summary, List<DayBalance> dayBalances) {
    }
}
