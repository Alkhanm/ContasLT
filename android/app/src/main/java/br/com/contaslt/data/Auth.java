package br.com.contaslt.data;

import java.util.HashMap;
import java.util.Map;

import br.com.contaslt.AppContainer;
import br.com.contaslt.dto.AuthResponse;
import br.com.contaslt.dto.User;
import br.com.contaslt.services.CatalogService;
import br.com.contaslt.services.Ctx;

/** Login e sessão (porte de frontend/src/stores/auth.ts). Métodos bloqueantes: chame fora da thread principal. */
public final class Auth {

    private final AppContainer app;

    public Auth(AppContainer app) {
        this.app = app;
    }

    public boolean isAuthenticated() {
        return app.prefs.token() != null && app.prefs.user() != null;
    }

    public User user() {
        return app.prefs.user();
    }

    public void login(String server, String email, String password) {
        app.prefs.setServer(server);
        Map<String, String> body = new HashMap<>();
        body.put("email", email);
        body.put("password", password);
        apply(app.api.post("/api/auth/login", body, AuthResponse.class));
        ensureSession();
    }

    /** Conta nova: o aparelho cria as categorias padrão e a conta principal. */
    public void register(String server, String name, String email, String password) {
        app.prefs.setServer(server);
        Map<String, String> body = new HashMap<>();
        body.put("name", name);
        body.put("email", email);
        body.put("password", password);
        apply(app.api.post("/api/auth/register", body, AuthResponse.class));
        ensureSession();
        seedIfNew();
    }

    /** Login com Google: entra, vincula pelo e-mail ou cria a conta (aí com as categorias padrão). */
    public void loginWithGoogle(String server, String idToken) {
        app.prefs.setServer(server);
        Map<String, String> body = new HashMap<>();
        body.put("idToken", idToken);
        AuthResponse res = app.api.post("/api/auth/google", body, AuthResponse.class);
        apply(res);
        ensureSession();
        if (res.created) {
            seedIfNew();
        }
    }

    /** Manda o código de recuperação para o e-mail (o servidor não diz se a conta existe). */
    public void forgotPassword(String server, String email) {
        app.prefs.setServer(server);
        Map<String, String> body = new HashMap<>();
        body.put("email", email);
        app.api.post("/api/auth/forgot-password", body, null);
    }

    /** Nova senha com o código do e-mail; já entra na conta. */
    public void resetPassword(String server, String email, String code, String password) {
        app.prefs.setServer(server);
        Map<String, String> body = new HashMap<>();
        body.put("email", email);
        body.put("code", code);
        body.put("password", password);
        apply(app.api.post("/api/auth/reset-password", body, AuthResponse.class));
        ensureSession();
    }

    private void seedIfNew() {
        try {
            app.session.syncNow();
        } catch (RuntimeException e) {
            // sem rede agora: as categorias padrão sobem na próxima sincronização
        }
        if (Ctx.db().isEmpty()) {
            CatalogService.seedDefaults();
            Ctx.db().flush();
        }
    }

    public void ensureSession() {
        User u = app.prefs.user();
        if (u != null && app.prefs.token() != null) {
            app.session.open(u.id);
        }
    }

    /**
     * Sair: apaga os dados deste usuário do aparelho, a não ser que ainda haja gravações que não chegaram ao
     * servidor (ficam guardadas e sobem no próximo login).
     */
    public void logout() {
        User u = app.prefs.user();
        if (u != null && app.session.isOpen()) {
            app.session.syncNow();
            boolean keep = app.session.store().pendingCount() > 0;
            if (keep) {
                app.session.close();
            } else {
                app.session.wipe(u.id);
            }
        }
        if (u != null) {
            clearInbox(u.id);
        }
        br.com.contaslt.ui.GoogleSignIn.signOut(app.context);
        expire();
    }

    /**
     * Exclui a conta no servidor (confirmando a senha) e apaga os dados deste usuário do aparelho, inclusive o que
     * ainda não tinha subido.
     */
    public void deleteAccount(String password) {
        Map<String, String> body = new HashMap<>();
        User u = app.prefs.user();
        body.put("email", u == null ? null : u.email);
        body.put("password", password);
        deleteAccount(body);
    }

    /** Exclusão confirmada pelo Google (contas que entram por ele e não têm senha). */
    public void deleteAccountWithGoogle(String idToken) {
        Map<String, String> body = new HashMap<>();
        body.put("googleIdToken", idToken);
        deleteAccount(body);
    }

    private void deleteAccount(Map<String, String> body) {
        User u = app.prefs.user();
        if (u == null) {
            throw new ApiException(401, "Sua sessão expirou. Entre novamente.");
        }
        app.api.post("/api/auth/delete-account", body, null);
        app.session.wipe(u.id);
        clearInbox(u.id);
        expire();
    }

    /** As sugestões lidas das notificações só existem no aparelho: saem junto com a conta (política de privacidade). */
    private void clearInbox(long userId) {
        app.context.getSharedPreferences("contaslt-inbox-u" + userId, android.content.Context.MODE_PRIVATE).edit().clear().apply();
    }

    /** Sessão expirada: pede login de novo, sem apagar os dados do aparelho. */
    public void expire() {
        app.prefs.clearSession();
        app.session.close();
    }

    private void apply(AuthResponse res) {
        app.prefs.setSession(res.token, res.user);
    }
}
