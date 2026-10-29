package br.com.contaslt.transfer;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    Optional<Transfer> findByIdAndUserId(Long id, Long userId);

    /** Pagamentos registrados para uma fatura: transferências cujo destino é o cartão, marcadas com o mês da fatura. */
    @Query("select t from Transfer t where t.toTransaction.account.id = :cardId and t.invoiceMonth = :month")
    List<Transfer> findInvoicePayments(@Param("cardId") Long cardId, @Param("month") LocalDate month);
}
