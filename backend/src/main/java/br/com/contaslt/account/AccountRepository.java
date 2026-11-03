package br.com.contaslt.account;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByUserIdOrderByArchivedAscNameAsc(Long userId);

    Optional<Account> findByIdAndUserId(Long id, Long userId);

    @Query("""
        select coalesce(sum(case when t.type = br.com.contaslt.common.TransactionType.INCOME then t.amount else -t.amount end), 0)
        from Transaction t where t.account.id = :accountId and t.paid = true
        """)
    BigDecimal sumPaidMovements(@Param("accountId") Long accountId);

    /** Movimentos pagos de todas as contas do usuário, agrupados por conta (uma consulta para a lista toda). */
    @Query("""
        select t.account.id, coalesce(sum(case when t.type = br.com.contaslt.common.TransactionType.INCOME then t.amount else -t.amount end), 0)
        from Transaction t where t.account.user.id = :userId and t.paid = true group by t.account.id
        """)
    List<Object[]> sumPaidMovementsByAccount(@Param("userId") Long userId);
}
