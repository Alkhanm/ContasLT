package br.com.contaslt.domain;

/** Arquivo que não dá para ler: a mensagem vai para o usuário (o serviço a transforma em erro de regra de negócio). */
public class ParseException extends RuntimeException {
    public ParseException(String message) {
        super(message);
    }
}
