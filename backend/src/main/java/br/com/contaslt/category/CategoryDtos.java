package br.com.contaslt.category;

import br.com.contaslt.common.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CategoryDtos {

    private CategoryDtos() {
    }

    public record CategoryRequest(
        @NotBlank(message = "Informe o nome da categoria") @Size(max = 60) String name,
        @NotNull(message = "Informe o tipo") TransactionType type,
        @NotBlank(message = "Escolha uma cor") @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "Cor inválida") String color,
        @Size(max = 40) String icon,
        Boolean archived,
        Long parentId) {
    }

    public record CategoryDto(Long id, String name, TransactionType type, String color, String icon, boolean archived,
            Long parentId) {
    }
}
