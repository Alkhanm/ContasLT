package br.com.contaslt.transaction;

import br.com.contaslt.card.CardInvoiceDtos.CardInvoiceDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
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
        @Valid @Size(max = 200) List<ItemRequest> items,
        /** Pagamento de fatura de cartão: as compras do cartão na fatura do mês ficam agregadas nesta despesa. */
        Long invoiceCardId,
        /** Mês da fatura (AAAA-MM); se vazio com cartão informado, usa a fatura de vencimento mais próximo. */
        YearMonth invoiceMonth) {
    }

    public record PaidRequest(@NotNull Boolean paid) {
    }

    /** Alcance de uma edição em ocorrência de conta recorrente. */
    public enum EditScope {
        /** Só este lançamento. */
        THIS,
        /** Este e os próximos vencimentos pendentes da mesma conta recorrente. */
        FOLLOWING
    }

    public record TransactionDto(Long id, String description, BigDecimal amount, TransactionType type,
            LocalDate dueDate, boolean paid, String notes, Long accountId, String accountName,
            Long categoryId, String categoryName, String categoryColor, String categoryIcon,
            Long recurringBillId, Integer installmentNumber, Integer installmentTotal,
            /** Só em ocorrências de conta recorrente: data prevista pela regra e valor estimado por ela. */
            LocalDate scheduledDate, BigDecimal expectedAmount, String externalId,
            Long transferId, Long transferPeerTransactionId, Long transferPeerAccountId, String transferPeerAccountName,
            String merchant, String receiptFile, String receiptAccessKey, Long importBatchId, List<ItemDto> items,
            /** Pagamento de fatura: cartão e mês da fatura cujas compras esta despesa agrega; {@code invoice} traz o resumo. */
            Long invoiceCardId, String invoiceCardName, YearMonth invoiceMonth, CardInvoiceDto invoice) {

        public TransactionDto withInvoice(CardInvoiceDto inv) {
            return new TransactionDto(id, description, amount, type, dueDate, paid, notes, accountId, accountName,
                categoryId, categoryName, categoryColor, categoryIcon, recurringBillId, installmentNumber, installmentTotal,
                scheduledDate, expectedAmount, externalId, transferId, transferPeerTransactionId, transferPeerAccountId,
                transferPeerAccountName, merchant, receiptFile, receiptAccessKey, importBatchId, items,
                invoiceCardId, invoiceCardName, invoiceMonth, inv);
        }
    }

    /**
     * Totais da listagem, em dois planos.
     * <p><b>Resultado</b> (segue os filtros da lista): {@code income}/{@code expense} ignoram pernas de transferência;
     * {@code balance} = receitas − despesas; transferOut/transferIn somam o que saiu e entrou por movimentação interna.</p>
     * <p><b>Caixa</b> (só o recorte de contas: todas as ativas ou a filtrada; ignora os demais filtros, como o extrato
     * do banco): {@code previousBalance} é o saldo das contas no início do período (saldo inicial + tudo que estava pago
     * antes); {@code inflow}/{@code outflow} são as entradas e saídas pagas no período, transferências incluídas;
     * {@code currentBalance} = anterior + entradas − saídas, o saldo que bate com o banco; {@code projectedBalance}
     * soma ainda o que está pendente no período.</p>
     */
    public record Summary(BigDecimal income, BigDecimal expense, BigDecimal balance,
            BigDecimal pendingIncome, BigDecimal pendingExpense, BigDecimal transferOut, BigDecimal transferIn,
            int count, BigDecimal previousBalance, BigDecimal inflow, BigDecimal outflow, BigDecimal currentBalance,
            BigDecimal projectedBalance) {

        public Summary withCash(BigDecimal previous, BigDecimal in, BigDecimal out, BigDecimal pendingNet) {
            BigDecimal current = previous.add(in).subtract(out);
            return new Summary(income, expense, balance, pendingIncome, pendingExpense, transferOut, transferIn,
                count, previous, in, out, current, current.add(pendingNet));
        }
    }

    /** Saldo das contas (todas ou a filtrada) ao fim do dia, contando só o que está pago, como o saldo da conta. */
    public record DayBalance(LocalDate date, BigDecimal balance) {
    }

    /** {@code invoice}: só quando a lista é de um cartão de crédito visto por fatura. */
    public record TransactionPage(List<TransactionDto> items, Summary summary, List<DayBalance> dayBalances,
            CardInvoiceDto invoice) {
    }
}
