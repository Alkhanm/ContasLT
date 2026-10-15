package br.com.contaslt.account;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AccountDtos {

    private AccountDtos() {
    }

    public record AccountRequest(
        @NotBlank(message = "Informe o nome da conta") @Size(max = 80) String name,
        @NotNull(message = "Informe o tipo") AccountType type,
        BigDecimal initialBalance,
        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "Cor inválida") String color,
        Boolean archived) {
    }

    public record AccountDto(Long id, String name, AccountType type, BigDecimal initialBalance,
            String color, boolean archived, BigDecimal balance) {
    }
}
