package br.com.contaslt.transfer;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRejectionRepository extends JpaRepository<TransferRejection, Long> {

    List<TransferRejection> findByUserId(Long userId);

    Optional<TransferRejection> findByUserIdAndKeyAAndKeyB(Long userId, String keyA, String keyB);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);
}
