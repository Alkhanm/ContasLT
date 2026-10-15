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

    public User get() {
        return users.findById(id()).orElseThrow(() -> new NotFoundException("Usuário não encontrado"));
    }
}
