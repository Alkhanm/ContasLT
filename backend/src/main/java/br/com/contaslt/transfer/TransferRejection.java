package br.com.contaslt.transfer;

import java.time.LocalDateTime;

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
import jakarta.persistence.Table;

/**
 * Par recusado no cruzamento de transferências. As chaves generalizam: ocorrência de conta recorrente vira
 * {@code bill:<id>} (a recusa cobre todos os meses das duas regras), lançamento avulso vira {@code tx:<id>}.
 */
@Entity
@Table(name = "transfer_rejections")
public class TransferRejection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "a_key", nullable = false)
    private String keyA;

    @Column(name = "b_key", nullable = false)
    private String keyB;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public static String keyOf(Transaction t) {
        return t.getRecurringBill() != null ? "bill:" + t.getRecurringBill().getId() : "tx:" + t.getId();
    }

    /** Chave do par, independente da ordem. */
    public static String pairKey(Transaction a, Transaction b) {
        return pairKey(keyOf(a), keyOf(b));
    }

    public static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getKeyA() { return keyA; }
    public String getKeyB() { return keyB; }
    public void setPair(String a, String b) {
        if (a.compareTo(b) <= 0) {
            this.keyA = a;
            this.keyB = b;
        } else {
            this.keyA = b;
            this.keyB = a;
        }
    }
    public String pairKey() { return keyA + "|" + keyB; }
    /** As duas chaves são de contas recorrentes: a recusa vale para todos os meses. */
    public boolean coversRecurring() { return keyA.startsWith("bill:") && keyB.startsWith("bill:"); }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
