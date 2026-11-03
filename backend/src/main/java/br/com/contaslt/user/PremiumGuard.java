package br.com.contaslt.user;

import org.springframework.stereotype.Component;

import br.com.contaslt.common.ForbiddenException;
import br.com.contaslt.config.AppProperties;

/**
 * Recursos "premium" (leitura de comprovantes, backup): só para usuários com {@code users.premium = TRUE}, marca
 * que se liga direto no banco. {@code PREMIUM_ENFORCE=false} libera tudo para todos (uso local, testes).
 */
@Component
public class PremiumGuard {

    private final boolean enforce;

    public PremiumGuard(AppProperties props) {
        this.enforce = props.premium() == null || props.premium().enforceOrDefault();
    }

    /** {@code message} é a frase completa do 403 (cada recurso tem a sua, com a concordância certa). */
    public void require(User user, String message) {
        if (enforce && !user.isPremium()) {
            throw new ForbiddenException(message);
        }
    }
}
