package br.com.contaslt.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
        @NotBlank(message = "Informe seu nome") @Size(max = 120) String name,
        @NotBlank(message = "Informe o e-mail") @Email(message = "E-mail inválido") @Size(max = 190) String email,
        @NotBlank(message = "Informe a senha") @Size(min = 6, max = 72, message = "A senha precisa ter entre 6 e 72 caracteres") String password) {
    }

    public record LoginRequest(
        @NotBlank(message = "Informe o e-mail") String email,
        @NotBlank(message = "Informe a senha") String password) {
    }

    /**
     * Exclusão de conta (vale do app e da página web, sem token): confirma com e-mail e senha ou, para quem entra
     * pelo Google, com um ID token do Google da mesma conta.
     */
    public record DeleteAccountRequest(String email, String password, String googleIdToken) {
    }

    /** Pede o código de recuperação de senha. */
    public record ForgotPasswordRequest(
        @NotBlank(message = "Informe o e-mail") @Email(message = "E-mail inválido") String email) {
    }

    /** Troca a senha com o código recebido por e-mail. */
    public record ResetPasswordRequest(
        @NotBlank(message = "Informe o e-mail") String email,
        @NotBlank(message = "Informe o código") String code,
        @NotBlank(message = "Informe a nova senha") @Size(min = 6, max = 72, message = "A senha precisa ter entre 6 e 72 caracteres") String password) {
    }

    /** Login com Google: o ID token entregue pelo Google Identity Services (web) ou pelo Credential Manager (Android). */
    public record GoogleLoginRequest(@NotBlank(message = "Token do Google ausente") String idToken) {
    }

    /** {@code premium}: pode usar a leitura de comprovantes. */
    public record UserDto(Long id, String name, String email, boolean premium) {
    }

    /** {@code created}: a conta acabou de ser criada (o aparelho cria as categorias padrão). */
    public record AuthResponse(String token, UserDto user, boolean created) {
    }
}
