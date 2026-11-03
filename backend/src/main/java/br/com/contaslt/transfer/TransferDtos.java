package br.com.contaslt.transfer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import br.com.contaslt.transaction.TransactionDtos.TransactionDto;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class TransferDtos {

    private TransferDtos() {
    }

    /** Criação direta de uma transferência: gera as duas pernas já vinculadas. */
    public record TransferRequest(
        @NotNull(message = "Escolha a conta de origem") Long fromAccountId,
        @NotNull(message = "Escolha a conta de destino") Long toAccountId,
        @NotNull(message = "Informe o valor") @Positive(message = "O valor deve ser maior que zero") BigDecimal amount,
        @NotNull(message = "Informe a data") LocalDate date,
        @Size(max = 140) String description,
        Boolean paid) {
    }

    /** Vincula dois lançamentos existentes (uma despesa e uma receita em contas diferentes). */
    public record LinkRequest(
        @NotNull Long transactionId,
        @NotNull Long otherTransactionId) {
    }

    /** Resposta da recusa: se cobriu duas contas recorrentes (vale para todos os meses). */
    public record RejectResult(boolean recurring) {
    }

    public record TransferDto(Long id, TransactionDto from, TransactionDto to) {
    }

    /** Par candidato encontrado pelo cruzamento automático. */
    public record Suggestion(TransactionDto from, TransactionDto to, int daysApart, int score) {
    }

    /** {@code rejected}: quantos pares o usuário já recusou (podem ser esquecidos em DELETE /api/transfers/rejections). */
    public record SuggestionList(List<Suggestion> items, long rejected) {
    }
}
