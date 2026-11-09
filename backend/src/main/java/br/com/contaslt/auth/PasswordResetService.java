package br.com.contaslt.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.contaslt.common.BusinessException;
import br.com.contaslt.mail.Mailer;
import br.com.contaslt.user.User;
import br.com.contaslt.user.UserRepository;

/**
 * "Esqueci minha senha" com código de 6 dígitos por e-mail (mesmo fluxo no PWA e no Android, sem link):
 * <ol>
 *   <li>{@link #requestCode}: sempre responde igual, exista ou não a conta (não revela quem é cliente). O e-mail sai
 *   numa thread à parte, para o tempo de resposta também não denunciar.</li>
 *   <li>{@link #reset}: confere o código e troca a senha. Só o hash do código fica no banco; vale 15 minutos, aceita
 *   {@value #MAX_ATTEMPTS} tentativas e é de uso único. Pedir um código novo invalida os anteriores.</li>
 * </ol>
 * Também serve para quem entrou só pelo Google definir uma senha.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    static final Duration TTL = Duration.ofMinutes(15);
    static final int MAX_ATTEMPTS = 5;
    /** Códigos por conta a cada {@link #TTL}: segura quem tenta lotar a caixa de alguém. */
    static final int MAX_CODES_PER_WINDOW = 3;
    static final String INVALID = "Código inválido ou expirado. Peça um novo código.";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final Mailer mailer;
    private final SecureRandom rng = new SecureRandom();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "password-reset-mail");
        t.setDaemon(true);
        return t;
    });

    public PasswordResetService(UserRepository users, PasswordEncoder passwordEncoder, JdbcTemplate jdbc, Mailer mailer) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.mailer = mailer;
    }

    @Transactional
    public void requestCode(String email) {
        Optional<User> found = users.findByEmailIgnoreCase(email.trim());
        if (found.isEmpty()) {
            return;
        }
        User user = found.get();
        LocalDateTime now = LocalDateTime.now();
        Integer recent = jdbc.queryForObject(
            "SELECT COUNT(*) FROM password_reset_codes WHERE user_id = ? AND created_at > ?", Integer.class,
            user.getId(), now.minus(TTL));
        if (recent != null && recent >= MAX_CODES_PER_WINDOW) {
            log.info("Recuperação de senha: limite de códigos atingido para o usuário {}", user.getId());
            return;
        }
        String code = String.format("%06d", rng.nextInt(1_000_000));
        jdbc.update("UPDATE password_reset_codes SET used_at = ? WHERE user_id = ? AND used_at IS NULL", now, user.getId());
        jdbc.update("INSERT INTO password_reset_codes (user_id, code_hash, expires_at, attempts, created_at) VALUES (?, ?, ?, 0, ?)",
            user.getId(), hash(user.getId(), code), now.plus(TTL), now);
        String to = user.getEmail();
        String name = user.getName();
        if (!mailer.enabled()) {
            log.warn("BREVO_API_KEY ausente: código de recuperação de {} = {} (só em desenvolvimento)", to, code);
            return;
        }
        sender.submit(() -> {
            try {
                mailer.send(to, name, "Seu código do ContasLT: " + code, text(name, code), html(name, code));
            } catch (RuntimeException e) {
                log.error("Falha ao enviar o código de recuperação para o usuário {}: {}", user.getId(), e.getMessage());
            }
        });
    }

    /** Troca a senha se o código conferir; devolve o usuário para já entrar. */
    @Transactional(noRollbackFor = BusinessException.class)
    public User reset(String email, String code, String newPassword) {
        User user = users.findByEmailIgnoreCase(email.trim()).orElseThrow(() -> new BusinessException(INVALID));
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, code_hash, attempts FROM password_reset_codes WHERE user_id = ? AND used_at IS NULL AND expires_at > ? "
                + "ORDER BY id DESC", user.getId(), now);
        if (rows.isEmpty()) {
            throw new BusinessException(INVALID);
        }
        Map<String, Object> row = rows.get(0);
        long id = ((Number) row.get("id")).longValue();
        int attempts = ((Number) row.get("attempts")).intValue();
        String typed = code == null ? "" : code.replaceAll("\\D", "");
        if (!MessageDigest.isEqual(hash(user.getId(), typed).getBytes(StandardCharsets.US_ASCII),
                String.valueOf(row.get("code_hash")).getBytes(StandardCharsets.US_ASCII))) {
            attempts++;
            if (attempts >= MAX_ATTEMPTS) {
                jdbc.update("UPDATE password_reset_codes SET attempts = ?, used_at = ? WHERE id = ?", attempts, now, id);
                throw new BusinessException("Muitas tentativas erradas. Peça um novo código.");
            }
            jdbc.update("UPDATE password_reset_codes SET attempts = ? WHERE id = ?", attempts, id);
            throw new BusinessException("Código incorreto. Confira o e-mail e tente de novo.");
        }
        jdbc.update("UPDATE password_reset_codes SET used_at = ? WHERE user_id = ? AND used_at IS NULL", now, user.getId());
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        return users.save(user);
    }

    private static String hash(long userId, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((userId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String firstName(String name) {
        return name == null || name.isBlank() ? "" : " " + name.trim().split("\\s+")[0];
    }

    private static String text(String name, String code) {
        return "Olá" + firstName(name) + ",\n\n"
            + "Use este código para criar uma nova senha no ContasLT:\n\n" + code + "\n\n"
            + "Ele vale por 15 minutos. Se não foi você que pediu, ignore este e-mail: sua senha continua a mesma.\n\n"
            + "ContasLT";
    }

    private static String html(String name, String code) {
        String safeName = firstName(name).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return """
            <div style="font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:0 auto;padding:24px;color:#14201a">
              <p style="font-size:18px;font-weight:bold;margin:0 0 16px">ContasLT</p>
              <p>Olá%s,</p>
              <p>Use este código para criar uma nova senha no ContasLT:</p>
              <p style="font-size:32px;font-weight:bold;letter-spacing:8px;margin:24px 0;color:#1b5e3f">%s</p>
              <p style="color:#5b6b62;font-size:14px">Ele vale por 15 minutos. Se não foi você que pediu, ignore este e-mail: sua senha continua a mesma.</p>
            </div>
            """.formatted(safeName, code);
    }
}
