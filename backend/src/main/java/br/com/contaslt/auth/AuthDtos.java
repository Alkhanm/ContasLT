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

    public record UserDto(Long id, String name, String email) {
    }

    public record AuthResponse(String token, UserDto user) {
    }
}
