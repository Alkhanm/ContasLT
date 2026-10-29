package br.com.contaslt.transfer;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    Optional<Transfer> findByIdAndUserId(Long id, Long userId);
}
