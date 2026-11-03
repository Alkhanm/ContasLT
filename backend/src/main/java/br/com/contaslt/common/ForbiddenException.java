package br.com.contaslt.common;

/** O usuário está autenticado, mas este recurso não é para ele (403). */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
