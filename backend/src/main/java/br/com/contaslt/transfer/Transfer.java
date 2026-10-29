package br.com.contaslt.transfer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import br.com.contaslt.transaction.Transaction;
import br.com.contaslt.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/** Vínculo entre a perna de saída (despesa) e a perna de entrada (receita) de uma movimentação interna. */
@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_transaction_id")
    private Transaction fromTransaction;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_transaction_id")
    private Transaction toTransaction;

    /** Quando o destino é um cartão de crédito: primeiro dia do mês da fatura que este pagamento quita. */
    @Column(name = "invoice_month")
    private LocalDate invoiceMonth;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Transaction getFromTransaction() { return fromTransaction; }
    public void setFromTransaction(Transaction fromTransaction) { this.fromTransaction = fromTransaction; }
    public Transaction getToTransaction() { return toTransaction; }
    public void setToTransaction(Transaction toTransaction) { this.toTransaction = toTransaction; }
    public YearMonth getInvoiceMonth() { return invoiceMonth == null ? null : YearMonth.from(invoiceMonth); }
    public void setInvoiceMonth(YearMonth month) { this.invoiceMonth = month == null ? null : month.atDay(1); }
    public LocalDateTime getCreatedAt() { return createdAt; }

    /** A outra perna em relação à informada. */
    public Transaction peerOf(Transaction t) {
        return fromTransaction.getId().equals(t.getId()) ? toTransaction : fromTransaction;
    }
}
