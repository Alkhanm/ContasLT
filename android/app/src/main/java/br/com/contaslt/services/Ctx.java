package br.com.contaslt.services;

import java.util.LinkedHashMap;
import java.util.Map;

import br.com.contaslt.data.ApiException;
import br.com.contaslt.data.LocalStore;

/**
 * Acesso dos serviços aos dados do usuário (porte de frontend/src/services/context.ts). Em produção é a sessão
 * aberta; nos testes, um store em memória. Os serviços leem e gravam de forma síncrona na memória; quem chama
 * (o {@code Repo} da interface) roda fora da thread principal e espera o banco com {@code flush()}.
 */
public final class Ctx {

    private static volatile LocalStore store;
    private static volatile boolean testing;
    /** Primeira sincronização completa feita neste aparelho (antes disso não se geram vencimentos). */
    private static volatile boolean initialSyncDone;

    private Ctx() {
    }

    public static LocalStore db() {
        LocalStore s = store;
        if (s == null) {
            throw new IllegalStateException("Sessão local não aberta");
        }
        return s;
    }

    public static void setStore(LocalStore s) {
        store = s;
        testing = false;
    }

    public static void useStoreForTests(LocalStore s) {
        store = s;
        testing = s != null;
    }

    public static boolean isTestStore() {
        return testing;
    }

    public static void setInitialSyncDone(boolean done) {
        initialSyncDone = done;
    }

    /** Pode gerar vencimentos de recorrentes (ver recurring.ts canGenerate). */
    public static boolean canGenerate() {
        return testing || initialSyncDone;
    }

    // ---------- Erros (os mesmos do PWA) ----------

    /** Regra de negócio violada (era o 422 do backend): a mensagem vai direto para o usuário. */
    public static class BusinessException extends ApiException {
        public BusinessException(String message) {
            super(422, message);
        }
    }

    public static class NotFoundException extends ApiException {
        public NotFoundException(String message) {
            super(404, message);
        }
    }

    /** Validação de campos (era o 400 com errors). */
    public static class ValidationException extends ApiException {
        public ValidationException(Map<String, String> errors) {
            super(400, "Dados inválidos", errors);
        }
    }

    /** Junta erros de campo e lança se houver algum (validate do PWA). */
    public static final class Checks {
        private final Map<String, String> errors = new LinkedHashMap<>();

        public Checks check(String field, boolean ok, String message) {
            if (!ok) {
                errors.putIfAbsent(field, message);
            }
            return this;
        }

        public void throwIfAny() {
            if (!errors.isEmpty()) {
                throw new ValidationException(errors);
            }
        }
    }

    public static Checks validate() {
        return new Checks();
    }

    public static <T> T require(T value, String message) {
        if (value == null) {
            throw new NotFoundException(message);
        }
        return value;
    }
}
