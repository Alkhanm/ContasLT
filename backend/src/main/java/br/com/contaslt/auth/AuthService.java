package br.com.contaslt.auth;

import java.time.Instant;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.contaslt.account.AccountService;
import br.com.contaslt.auth.AuthDtos.AuthResponse;
import br.com.contaslt.auth.AuthDtos.LoginRequest;
import br.com.contaslt.auth.AuthDtos.RegisterRequest;
import br.com.contaslt.auth.AuthDtos.UserDto;
import br.com.contaslt.category.CategoryService;
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.config.AppProperties;
import br.com.contaslt.user.User;
import br.com.contaslt.user.UserRepository;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final AppProperties props;
    private final CategoryService categories;
    private final AccountService accounts;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder,
            AppProperties props, CategoryService categories, AccountService accounts) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.props = props;
        this.categories = categories;
        this.accounts = accounts;
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
        categories.seedDefaults(user);
        accounts.seedDefault(user);
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
