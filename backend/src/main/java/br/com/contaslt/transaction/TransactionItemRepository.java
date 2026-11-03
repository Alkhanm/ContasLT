package br.com.contaslt.transaction;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionItemRepository extends JpaRepository<TransactionItem, Long> {

    /** Itens de comprovante do usuário num período, com lançamento e categoria (e pai) já carregados, em ordem de compra. */
    @Query("select i from TransactionItem i join fetch i.transaction t left join fetch t.category c left join fetch c.parent "
        + "where t.user.id = :userId and t.dueDate between :from and :to order by t.dueDate asc, t.id asc, i.position asc")
    List<TransactionItem> findByUserAndPeriod(@Param("userId") Long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
