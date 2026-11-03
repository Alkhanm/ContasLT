package br.com.contaslt.user;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import br.com.contaslt.common.NotFoundException;

/** Resolve o usuário autenticado a partir do JWT da requisição atual. */
@Component
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    public Long id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Requisição sem usuário autenticado");
        }
        return Long.valueOf(jwt.getSubject());
    }

    /**
     * Usuário da requisição sem consulta ao banco: id, nome, e-mail e premium vêm das claims do JWT. Com o
     * Postgres a ~150 ms de distância, poupar uma ida por requisição conta. Tokens antigos, sem a claim
     * {@code premium}, caem na consulta. Mudar premium no banco passa a valer no próximo login.
     */
    public User get() {
        Jwt jwt = jwt();
        Boolean premium = jwt.getClaim("premium");
        if (premium == null) {
            return load();
        }
        return User.fromClaims(Long.valueOf(jwt.getSubject()), jwt.getClaimAsString("name"), jwt.getClaimAsString("email"), premium);
    }

    private static Jwt jwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Requisição sem usuário autenticado");
        }
        return jwt;
    }

    /** O usuário de verdade (uma consulta), para quem precisa dos campos. */
    public User load() {
        return users.findById(id()).orElseThrow(() -> new NotFoundException("Usuário não encontrado"));
    }
}
