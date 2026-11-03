package br.com.contaslt.receipt;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReceiptJobRepository extends JpaRepository<ReceiptJob, Long> {

    Optional<ReceiptJob> findByIdAndUserId(Long id, Long userId);

    Optional<ReceiptJob> findFirstByUserIdAndReceiptFileOrderByIdDesc(Long userId, String receiptFile);

    /** Pendentes, mais as em execução cujo prazo venceu (worker morreu no meio), na ordem de chegada. */
    @Query("select j from ReceiptJob j where j.status = :pending or (j.status = :running and j.leasedUntil < :now) order by j.id asc")
    List<ReceiptJob> findClaimable(@Param("pending") ReceiptJob.Status pending, @Param("running") ReceiptJob.Status running,
            @Param("now") LocalDateTime now, Pageable page);
}
