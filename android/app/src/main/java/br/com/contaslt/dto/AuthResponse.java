package br.com.contaslt.dto;

/** Porte de AuthResponse (frontend/src/api/types.ts). Dinheiro em centavos. */
public class AuthResponse {
    public String token;
    public User user;
    /** A conta acabou de ser criada (cadastro ou primeiro login com Google). */
    public boolean created;
}
