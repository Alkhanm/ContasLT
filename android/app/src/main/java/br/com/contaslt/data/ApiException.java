package br.com.contaslt.data;

import java.util.Map;

/**
 * Erro com mensagem para o usuário (como o ApiError do PWA). status 0 = sem conexão; 401 = sessão expirada;
 * 422 = regra de negócio; 400 = validação, com {@link #errors} por campo.
 */
public class ApiException extends RuntimeException {

    public final int status;
    public final Map<String, String> errors;

    public ApiException(int status, String message) {
        this(status, message, java.util.Collections.emptyMap());
    }

    public ApiException(int status, String message, Map<String, String> errors) {
        super(message);
        this.status = status;
        this.errors = errors;
    }
}
