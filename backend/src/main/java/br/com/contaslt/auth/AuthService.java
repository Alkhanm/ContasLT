package br.com.contaslt.auth;

import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.contaslt.auth.AuthDtos.AuthResponse;
import br.com.contaslt.auth.GoogleTokenVerifier.GoogleIdentity;
import br.com.contaslt.auth.AuthDtos.DeleteAccountRequest;
import br.com.contaslt.auth.AuthDtos.LoginRequest;
import br.com.contaslt.auth.AuthDtos.RegisterRequest;
import br.com.contaslt.auth.AuthDtos.UserDto;
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.config.AppProperties;
import br.com.contaslt.receipt.ReceiptStorage;
import br.com.contaslt.user.User;
import br.com.contaslt.user.UserRepository;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final AppProperties props;
    private final JdbcTemplate jdbc;
    private final ReceiptStorage receipts;
    private final GoogleTokenVerifier google;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder, AppProperties props,
            JdbcTemplate jdbc, ReceiptStorage receipts, GoogleTokenVerifier google) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.props = props;
        this.jdbc = jdbc;
        this.receipts = receipts;
        this.google = google;
    }

    /**
     * Exclusão de conta (exigência da Play Store, pelo app e pela página web): confirma e-mail e senha (ou o Google,
     * para quem entra por ele) e apaga o
     * usuário com tudo o que é dele — dados sincronizados, fila e fotos de comprovantes e as tabelas da versão
     * anterior ao local-first. Não tem volta.
     */
    @Transactional
    public void deleteAccount(DeleteAccountRequest req) {
        User user;
        if (req.googleIdToken() != null && !req.googleIdToken().isBlank()) {
            GoogleIdentity g = google.verify(req.googleIdToken());
            user = users.findByGoogleSub(g.sub()).orElseThrow(() -> new BusinessException(
                "Esta conta Google não está vinculada a nenhuma conta do ContasLT"));
        } else {
            if (req.email() == null || req.email().isBlank() || req.password() == null || req.password().isEmpty()) {
                throw new BadCredentialsException("invalid");
            }
            user = checkPassword(req.email(), req.password());
        }
        Long id = user.getId();
        // Tabelas antigas (sem ON DELETE CASCADE para users), na ordem das referências.
        jdbc.update("DELETE FROM transfer_rejections WHERE user_id = ?", id);
        jdbc.update("UPDATE receipt_jobs SET transaction_id = NULL WHERE user_id = ?", id);
        jdbc.update("UPDATE transactions SET transfer_id = NULL WHERE user_id = ?", id);
        jdbc.update("DELETE FROM transfers WHERE user_id = ?", id);
        jdbc.update("DELETE FROM transaction_items WHERE transaction_id IN (SELECT id FROM transactions WHERE user_id = ?)", id);
        jdbc.update("DELETE FROM transactions WHERE user_id = ?", id);
        jdbc.update("DELETE FROM import_batches WHERE user_id = ?", id);
        jdbc.update("DELETE FROM category_rules WHERE user_id = ?", id);
        jdbc.update("DELETE FROM recurring_bills WHERE user_id = ?", id);
        jdbc.update("UPDATE categories SET parent_id = NULL WHERE user_id = ?", id);
        jdbc.update("DELETE FROM categories WHERE user_id = ?", id);
        jdbc.update("DELETE FROM accounts WHERE user_id = ?", id);
        // sync_records e receipt_jobs saem junto com o usuário (ON DELETE CASCADE).
        jdbc.update("DELETE FROM sync_records WHERE user_id = ?", id);
        jdbc.update("DELETE FROM receipt_jobs WHERE user_id = ?", id);
        jdbc.update("DELETE FROM users WHERE id = ?", id);
        receipts.deleteAll(id);
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = req.email().trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(email)) {
            throw new BusinessException("Já existe uma conta com este e-mail");
        }
        User user = new User();
        user.setName(req.name().trim());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user = users.save(user);
        // Categorias padrão e a conta principal são criadas pelo app no primeiro acesso (dados ficam no aparelho).
        return new AuthResponse(issueToken(user), toDto(user), true);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = checkPassword(req.email(), req.password());
        return new AuthResponse(issueToken(user), toDto(user), false);
    }

    /** Sessão para quem acabou de trocar a senha pelo código de recuperação. */
    public AuthResponse session(User user) {
        return new AuthResponse(issueToken(user), toDto(user), false);
    }

    /**
     * Login com Google. Conta já vinculada: entra. Senão, conta com o mesmo e-mail (confirmado pelo Google): vincula e
     * entra, e a pessoa passa a poder usar os dois jeitos. Senão, cria a conta (sem senha).
     */
    @Transactional
    public AuthResponse loginWithGoogle(String idToken) {
        GoogleIdentity g = google.verify(idToken);
        var linked = users.findByGoogleSub(g.sub());
        if (linked.isPresent()) {
            return new AuthResponse(issueToken(linked.get()), toDto(linked.get()), false);
        }
        if (g.email() == null || g.email().isBlank() || !g.emailVerified()) {
            throw new BusinessException("O Google não confirmou o e-mail desta conta. Use e-mail e senha.");
        }
        String email = g.email().trim().toLowerCase();
        var existing = users.findByEmailIgnoreCase(email);
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.getGoogleSub() != null) {
                throw new BusinessException("Este e-mail já está vinculado a outra conta Google");
            }
            user.setGoogleSub(g.sub());
            user = users.save(user);
            return new AuthResponse(issueToken(user), toDto(user), false);
        }
        User user = new User();
        String name = g.name() == null || g.name().isBlank() ? email.substring(0, email.indexOf('@')) : g.name().trim();
        user.setName(name.length() > 120 ? name.substring(0, 120) : name);
        user.setEmail(email);
        user.setGoogleSub(g.sub());
        user = users.save(user);
        return new AuthResponse(issueToken(user), toDto(user), true);
    }

    /** E-mail e senha conferem (conta só com Google, sem senha, nunca confere). */
    private User checkPassword(String email, String password) {
        User user = users.findByEmailIgnoreCase(email.trim()).orElseThrow(() -> new BadCredentialsException("invalid"));
        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BadCredentialsException("invalid");
        }
        return user;
    }

    public static UserDto toDto(User user) {
        return new UserDto(user.getId(), user.getName(), user.getEmail(), user.isPremium());
    }

    private String issueToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("contaslt")
            .subject(String.valueOf(user.getId()))
            .claim("email", user.getEmail())
            .claim("name", user.getName())
            // Com isto o backend monta o usuário da requisição sem consultar o banco (ver CurrentUser).
            .claim("premium", user.isPremium())
            .issuedAt(now)
            .expiresAt(now.plus(props.jwt().expiration()))
            .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
