package br.com.contaslt.account;

import java.math.BigDecimal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
        Boolean archived,
        /** Só para cartão de crédito: dia do fechamento e do vencimento da fatura. */
        @Min(value = 1, message = "Dia inválido") @Max(value = 28, message = "Use um dia entre 1 e 28") Integer closingDay,
        @Min(value = 1, message = "Dia inválido") @Max(value = 28, message = "Use um dia entre 1 e 28") Integer dueDay) {
    }

    public record AccountDto(Long id, String name, AccountType type, BigDecimal initialBalance,
            String color, boolean archived, BigDecimal balance, Integer closingDay, Integer dueDay) {
    }
}
