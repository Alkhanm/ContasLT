package br.com.contaslt.imports;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRuleRepository extends JpaRepository<CategoryRule, Long> {
    @EntityGraph(attributePaths = "category")
    List<CategoryRule> findByUserIdOrderByKeywordAsc(Long userId);

    Optional<CategoryRule> findByUserIdAndKeyword(Long userId, String keyword);

    Optional<CategoryRule> findByIdAndUserId(Long id, Long userId);
}
