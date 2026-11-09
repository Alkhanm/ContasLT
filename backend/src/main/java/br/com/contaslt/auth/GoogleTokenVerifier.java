package br.com.contaslt.auth;

import java.util.List;
import java.util.Set;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import br.com.contaslt.common.BusinessException;
import br.com.contaslt.config.AppProperties;

/**
 * Confere o ID token que o Google entrega ao PWA (Google Identity Services) e ao Android (Credential Manager):
 * assinatura pelas chaves públicas do Google, emissor, validade e {@code aud} igual a um dos nossos IDs de cliente.
 */
@Component
public class GoogleTokenVerifier {

    /** Dados da conta Google que o login usa. {@code emailVerified}: o Google confirmou que o e-mail é da pessoa. */
    public record GoogleIdentity(String sub, String email, boolean emailVerified, String name) {
    }

    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final List<String> clientIds;
    private volatile NimbusJwtDecoder decoder;

    public GoogleTokenVerifier(AppProperties props) {
        this.clientIds = props.google() == null ? List.of() : props.google().ids();
    }

    public boolean enabled() {
        return !clientIds.isEmpty();
    }

    public GoogleIdentity verify(String idToken) {
        if (!enabled()) {
            throw new BusinessException("O login com Google não está disponível");
        }
        Jwt jwt;
        try {
            jwt = decoder().decode(idToken);
        } catch (JwtException e) {
            throw new BusinessException("Não foi possível confirmar o login com o Google. Tente de novo.");
        }
        Object verified = jwt.getClaims().get("email_verified");
        boolean emailVerified = Boolean.TRUE.equals(verified) || "true".equals(String.valueOf(verified));
        String name = jwt.getClaimAsString("name");
        return new GoogleIdentity(jwt.getSubject(), jwt.getClaimAsString("email"), emailVerified, name);
    }

    private NimbusJwtDecoder decoder() {
        NimbusJwtDecoder d = decoder;
        if (d == null) {
            synchronized (this) {
                if (decoder == null) {
                    d = NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
                    d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), jwt -> {
                        String iss = jwt.getClaimAsString("iss");
                        List<String> aud = jwt.getAudience();
                        boolean ok = iss != null && ISSUERS.contains(iss) && aud != null && aud.stream().anyMatch(clientIds::contains)
                            && jwt.getSubject() != null;
                        return ok ? OAuth2TokenValidatorResult.success()
                            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Emissor ou cliente inválido", null));
                    }));
                    decoder = d;
                }
                d = decoder;
            }
        }
        return d;
    }
}
