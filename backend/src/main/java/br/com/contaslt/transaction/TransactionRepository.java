package br.com.contaslt.transaction;

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

    @EntityGraph(attributePaths = {"account", "category", "recurringBill"})
    Optional<Transaction> findByIdAndUserId(Long id, Long userId);

    boolean existsByAccountId(Long accountId);

    boolean existsByCategoryId(Long categoryId);

    List<Transaction> findByRecurringBillIdAndPaidFalse(Long recurringBillId);

    @Modifying
    @Query("delete from Transaction t where t.recurringBill.id = :billId and t.paid = false")
    void deleteByRecurringBillIdAndPaidFalse(@Param("billId") Long billId);

    @Modifying
    @Query("delete from Transaction t where t.importBatch.id = :batchId")
    int deleteByImportBatchId(@Param("batchId") Long batchId);

    @Modifying
    @Query("update Transaction t set t.recurringBill = null where t.recurringBill.id = :billId")
    void detachFromRecurringBill(@Param("billId") Long billId);

    @Query("select t.dueDate from Transaction t where t.recurringBill.id = :billId and t.dueDate between :from and :to")
    Set<LocalDate> findDueDatesByRecurringBill(@Param("billId") Long billId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @EntityGraph(attributePaths = {"account", "category"})
    @Query("select t from Transaction t where t.user.id = :userId and t.dueDate between :from and :to order by t.dueDate desc, t.id desc")
    List<Transaction> findByUserAndPeriod(@Param("userId") Long userId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @EntityGraph(attributePaths = {"account", "category"})
    @Query("select t from Transaction t where t.user.id = :userId and t.paid = false and t.dueDate < :today order by t.dueDate asc")
    List<Transaction> findOverdue(@Param("userId") Long userId, @Param("today") LocalDate today);

    @EntityGraph(attributePaths = {"account", "category"})
    @Query("select t from Transaction t where t.user.id = :userId and t.paid = false and t.dueDate between :from and :to order by t.dueDate asc")
    List<Transaction> findUpcoming(@Param("userId") Long userId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @EntityGraph(attributePaths = {"category"})
    @Query("select t from Transaction t where t.user.id = :userId and t.category is not null order by t.id desc limit 3000")
    List<Transaction> findRecentCategorized(@Param("userId") Long userId);

    @Query("select t.externalId from Transaction t where t.account.id = :accountId and t.externalId in :ids")
    Set<String> findExistingExternalIds(@Param("accountId") Long accountId, @Param("ids") Set<String> ids);

    @Query("select t from Transaction t where t.account.id = :accountId and t.dueDate between :from and :to")
    List<Transaction> findByAccountAndPeriod(@Param("accountId") Long accountId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    boolean existsByAccountIdAndExternalId(Long accountId, String externalId);
}
