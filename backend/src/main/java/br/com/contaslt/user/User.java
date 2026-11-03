package br.com.contaslt.user;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Pode mandar comprovantes para leitura (consome a GPU do dono do app). Só muda direto no banco. */
    @Column(nullable = false)
    private boolean premium;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    /**
     * Usuário desanexado montado a partir das claims do JWT: id, nome, e-mail e premium sem ir ao banco.
     * Serve como referência em {@code setUser(...)} (o Hibernate grava só o id) e para checagens de premium.
     */
    public static User fromClaims(Long id, String name, String email, boolean premium) {
        User u = new User();
        u.id = id;
        u.name = name;
        u.email = email;
        u.premium = premium;
        return u;
    }
    public String getEmail() { return email; }
    public boolean isPremium() { return premium; }
    public void setPremium(boolean premium) { this.premium = premium; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
