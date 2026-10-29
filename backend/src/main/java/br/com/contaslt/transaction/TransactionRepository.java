package br.com.contaslt.transaction;

import br.com.contaslt.common.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    @EntityGraph(attributePaths = {"account", "category", "recurringBill", "transfer"})
    Optional<Transaction> findByIdAndUserId(Long id, Long userId);

    boolean existsByAccountId(Long accountId);

    boolean existsByCategoryId(Long categoryId);

    List<Transaction> findByRecurringBillIdAndPaidFalse(Long recurringBillId);

    /** Ocorrências pendentes previstas depois de uma data (as "próximas" de um vencimento). */
    List<Transaction> findByRecurringBillIdAndPaidFalseAndScheduledDateAfter(Long recurringBillId, LocalDate after);

    /** Vencimentos pendentes de contas recorrentes ativas, ainda não ligados a extrato: candidatos a casar com linhas importadas. */
    @EntityGraph(attributePaths = {"account", "category", "recurringBill"})
    @Query("select t from Transaction t where t.user.id = :userId and t.recurringBill is not null and t.recurringBill.active = true "
        + "and t.paid = false and t.importBatch is null and t.externalId is null and t.dueDate between :from and :to")
    List<Transaction> findPendingOccurrences(@Param("userId") Long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Descrições e estabelecimentos dos últimos pagamentos de uma regra: ensinam como a conta aparece no extrato. */
    @Query("select coalesce(t.merchant, '') || ' ' || t.description from Transaction t where t.recurringBill.id = :billId and t.paid = true order by t.dueDate desc")
    List<String> findRecentPaidDescriptions(@Param("billId") Long billId, org.springframework.data.domain.Pageable page);

    boolean existsByRecurringBillIdAndScheduledDateAndIdNot(Long recurringBillId, LocalDate scheduledDate, Long id);

    @Modifying
    @Query("delete from Transaction t where t.recurringBill.id = :billId and t.paid = false")
    void deleteByRecurringBillIdAndPaidFalse(@Param("billId") Long billId);

    @Modifying
    @Query("delete from Transaction t where t.recurringBill.id = :billId and t.paid = false and t.scheduledDate > :after")
    void deleteByRecurringBillIdAndPaidFalseAndScheduledDateAfter(@Param("billId") Long billId, @Param("after") LocalDate after);

    @Modifying
    @Query("delete from Transaction t where t.importBatch.id = :batchId")
    int deleteByImportBatchId(@Param("batchId") Long batchId);

    /** Movimento líquido pago (receitas − despesas) das contas antes de uma data: ponto de partida do saldo por dia. */
    @Query("""
        select coalesce(sum(case when t.type = br.com.contaslt.common.TransactionType.INCOME then t.amount else -t.amount end), 0)
        from Transaction t where t.account.id in :accountIds and t.paid = true and t.dueDate < :before
        """)
    BigDecimal sumPaidMovementsBefore(@Param("accountIds") List<Long> accountIds, @Param("before") LocalDate before);

    /** Entradas e saídas das contas no período, separadas por pago/pendente. Cada linha: [TransactionType, Boolean paid, BigDecimal]. */
    @Query("""
        select t.type, t.paid, sum(t.amount) from Transaction t
        where t.account.id in :accountIds and t.dueDate between :from and :to group by t.type, t.paid
        """)
    List<Object[]> sumMovementsByTypeAndPaid(@Param("accountIds") List<Long> accountIds, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** Movimento líquido pago por dia, no período. Cada linha: [LocalDate, BigDecimal]. */
    @Query("""
        select t.dueDate, sum(case when t.type = br.com.contaslt.common.TransactionType.INCOME then t.amount else -t.amount end)
        from Transaction t where t.account.id in :accountIds and t.paid = true and t.dueDate between :from and :to
        group by t.dueDate order by t.dueDate
        """)
    List<Object[]> sumPaidMovementsByDay(@Param("accountIds") List<Long> accountIds, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Modifying
    @Query("update Transaction t set t.recurringBill = null where t.recurringBill.id = :billId")
    void detachFromRecurringBill(@Param("billId") Long billId);

    @Query("select t.scheduledDate from Transaction t where t.recurringBill.id = :billId and t.scheduledDate between :from and :to")
    Set<LocalDate> findScheduledDatesByRecurringBill(@Param("billId") Long billId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @EntityGraph(attributePaths = {"account", "category", "transfer"})
    @Query("select t from Transaction t where t.user.id = :userId and t.dueDate between :from and :to order by t.dueDate desc, t.id desc")
    List<Transaction> findByUserAndPeriod(@Param("userId") Long userId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @EntityGraph(attributePaths = {"account", "category", "transfer"})
    @Query("select t from Transaction t where t.user.id = :userId and t.paid = false and t.dueDate < :today order by t.dueDate asc")
    List<Transaction> findOverdue(@Param("userId") Long userId, @Param("today") LocalDate today);

    @EntityGraph(attributePaths = {"account", "category", "transfer"})
    @Query("select t from Transaction t where t.user.id = :userId and t.paid = false and t.dueDate between :from and :to order by t.dueDate asc")
    List<Transaction> findUpcoming(@Param("userId") Long userId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("select t.externalId from Transaction t where t.account.id = :accountId and t.externalId in :ids")
    Set<String> findExistingExternalIds(@Param("accountId") Long accountId, @Param("ids") Set<String> ids);

    @Query("select t from Transaction t where t.account.id = :accountId and t.dueDate between :from and :to")
    List<Transaction> findByAccountAndPeriod(@Param("accountId") Long accountId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    boolean existsByAccountIdAndExternalId(Long accountId, String externalId);

    /**
     * Lançamentos criados à mão (não vindos de importação nem de transferência) num período: candidatos a serem
     * vinculados a linhas de um extrato importado.
     */
    @EntityGraph(attributePaths = {"account", "category"})
    @Query("select t from Transaction t where t.user.id = :userId and t.importBatch is null and t.externalId is null "
        + "and t.transfer is null and t.recurringBill is null and t.dueDate between :from and :to")
    List<Transaction> findImportCandidates(@Param("userId") Long userId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * Compras registradas no cartão num período da fatura (sem pernas de transferência nem o ajuste de compras não
     * detalhadas): despesas somam, estornos (receitas) abatem.
     */
    @Query("""
        select coalesce(sum(case when t.type = br.com.contaslt.common.TransactionType.EXPENSE then t.amount else -t.amount end), 0)
        from Transaction t where t.account.id = :cardId and t.transfer is null and t.invoiceGap = false
        and t.dueDate between :from and :to
        """)
    BigDecimal sumCardPurchases(@Param("cardId") Long cardId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    List<Transaction> findByAccountIdAndInvoiceGapTrueAndDueDateBetween(Long cardId, LocalDate from, LocalDate to);

    /** Pernas de entrada de pagamento de fatura no cartão: candidatas a casar com "pagamento recebido" do OFX do cartão. */
    @EntityGraph(attributePaths = {"account", "category"})
    @Query("select t from Transaction t where t.account.id = :cardId and t.transfer is not null and t.importBatch is null "
        + "and t.type = br.com.contaslt.common.TransactionType.INCOME and t.dueDate between :from and :to")
    List<Transaction> findInvoicePaymentLegs(@Param("cardId") Long cardId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    Optional<Transaction> findFirstByUserIdAndReceiptAccessKey(Long userId, String receiptAccessKey);

    /** Candidatos a duplicata de um comprovante sem chave legível: mesma data e mesmo valor. */
    List<Transaction> findByUserIdAndTypeAndDueDateAndAmount(Long userId, TransactionType type, LocalDate dueDate, BigDecimal amount);

    @Modifying
    @Query("update Transaction t set t.importBatch = null, t.externalId = null, t.linkedImport = false "
        + "where t.importBatch.id = :batchId and t.linkedImport = true")
    int detachLinkedFromImportBatch(@Param("batchId") Long batchId);

    /** Lançamentos ainda não vinculados a nenhuma transferência, candidatos ao cruzamento entre contas. */
    @EntityGraph(attributePaths = {"account", "category"})
    @Query("select t from Transaction t where t.user.id = :userId and t.transfer is null and t.dueDate >= :from order by t.dueDate asc, t.id asc")
    List<Transaction> findUnlinkedSince(@Param("userId") Long userId, @Param("from") LocalDate from);
}
