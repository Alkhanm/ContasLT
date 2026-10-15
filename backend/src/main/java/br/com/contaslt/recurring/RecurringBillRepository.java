package br.com.contaslt.recurring;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecurringBillRepository extends JpaRepository<RecurringBill, Long> {

    @EntityGraph(attributePaths = {"account", "category"})
    List<RecurringBill> findByUserIdOrderByActiveDescDescriptionAsc(Long userId);

    @EntityGraph(attributePaths = {"account", "category"})
    List<RecurringBill> findByUserIdAndActiveTrue(Long userId);

    @EntityGraph(attributePaths = {"account", "category"})
    Optional<RecurringBill> findByIdAndUserId(Long id, Long userId);
}
