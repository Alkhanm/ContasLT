package br.com.contaslt.transfer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
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
        Boolean paid,
        /** Destino cartão de crédito: mês da fatura paga (AAAA-MM). Se vazio, usa a fatura de vencimento mais próximo. */
        YearMonth invoiceMonth) {
    }

    /** Vincula dois lançamentos existentes (uma despesa e uma receita em contas diferentes). */
    public record LinkRequest(
        @NotNull Long transactionId,
        @NotNull Long otherTransactionId,
        YearMonth invoiceMonth) {
    }

    public record TransferDto(Long id, TransactionDto from, TransactionDto to, YearMonth invoiceMonth) {
    }

    /** Par candidato encontrado pelo cruzamento automático. */
    public record Suggestion(TransactionDto from, TransactionDto to, int daysApart, int score) {
    }

    public record SuggestionList(List<Suggestion> items) {
    }
}
