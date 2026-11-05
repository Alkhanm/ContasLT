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

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder, AppProperties props,
            JdbcTemplate jdbc, ReceiptStorage receipts) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.props = props;
        this.jdbc = jdbc;
        this.receipts = receipts;
    }

    /**
     * Exclusão de conta (exigência da Play Store, pelo app e pela página web): confirma e-mail e senha e apaga o
     * usuário com tudo o que é dele — dados sincronizados, fila e fotos de comprovantes e as tabelas da versão
     * anterior ao local-first. Não tem volta.
     */
    @Transactional
    public void deleteAccount(DeleteAccountRequest req) {
        User user = users.findByEmailIgnoreCase(req.email().trim())
            .orElseThrow(() -> new BadCredentialsException("invalid"));
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("invalid");
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
        return new AuthResponse(issueToken(user), toDto(user));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = users.findByEmailIgnoreCase(req.email().trim())
            .orElseThrow(() -> new BadCredentialsException("invalid"));
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("invalid");
        }
        return new AuthResponse(issueToken(user), toDto(user));
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
