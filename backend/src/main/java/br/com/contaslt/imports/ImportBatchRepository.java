package br.com.contaslt.imports;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {
    @EntityGraph(attributePaths = "account")
    List<ImportBatch> findTop50ByUserIdOrderByIdDesc(Long userId);

    Optional<ImportBatch> findByIdAndUserId(Long id, Long userId);
}
