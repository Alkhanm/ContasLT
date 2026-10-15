package br.com.contaslt.category;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findByUserIdOrderByTypeAscNameAsc(Long userId);
    Optional<Category> findByIdAndUserId(Long id, Long userId);
}
