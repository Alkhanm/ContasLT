package br.com.contaslt.receipt;

import java.util.UUID;

import java.time.LocalDateTime;

import br.com.contaslt.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Uma foto de comprovante esperando (ou já com) a leitura feita pelo worker do computador do usuário. */
@Entity
@Table(name = "receipt_jobs")
public class ReceiptJob {

    public enum Status { PENDING, RUNNING, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    /** Lançamento do cliente (UUID do registro local) salvo com esta foto enquanto a leitura não tinha terminado. */
    @Column(name = "transaction_uuid")
    private UUID transactionUuid;

    @Column(name = "receipt_file", nullable = false)
    private String receiptFile;

    @Column(name = "qr_access_key")
    private String qrAccessKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "leased_until")
    private LocalDateTime leasedUntil;

    @Column(length = 20000)
    private String result;

    @Column(length = 500)
    private String error;

    @Column(nullable = false)
    private boolean delivered;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public UUID getTransactionUuid() { return transactionUuid; }
    public void setTransactionUuid(UUID transactionUuid) { this.transactionUuid = transactionUuid; }
    public String getReceiptFile() { return receiptFile; }
    public void setReceiptFile(String receiptFile) { this.receiptFile = receiptFile; }
    public String getQrAccessKey() { return qrAccessKey; }
    public void setQrAccessKey(String qrAccessKey) { this.qrAccessKey = qrAccessKey; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public LocalDateTime getLeasedUntil() { return leasedUntil; }
    public void setLeasedUntil(LocalDateTime leasedUntil) { this.leasedUntil = leasedUntil; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public boolean isDelivered() { return delivered; }
    public void setDelivered(boolean delivered) { this.delivered = delivered; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
