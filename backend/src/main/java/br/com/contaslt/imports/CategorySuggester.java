package br.com.contaslt.imports;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import br.com.contaslt.category.Category;
import br.com.contaslt.common.TextNormalizer;
import br.com.contaslt.common.TransactionType;

/**
 * Sugere categoria para uma descrição: 1) nome de categoria vindo do arquivo, 2) regras do usuário.
 * Não adivinha pelo histórico de lançamentos: é melhor ficar sem categoria do que mal categorizado.
 */
public class CategorySuggester {

    private final List<Category> categories;
    private final List<CategoryRule> rules;

    public CategorySuggester(List<Category> categories, List<CategoryRule> rules) {
        this.categories = categories;
        this.rules = rules.stream()
            .sorted(Comparator.comparingInt((CategoryRule r) -> r.getKeyword().length()).reversed())
            .toList();
    }

    public Optional<Category> suggest(String description, TransactionType type, String categoryHint) {
        if (categoryHint != null && !categoryHint.isBlank()) {
            String hint = TextNormalizer.normalize(categoryHint);
            Optional<Category> byName = categories.stream()
                .filter(c -> c.getType() == type && TextNormalizer.normalize(c.getName()).equals(hint))
                .findFirst();
            if (byName.isPresent()) {
                return byName;
            }
        }
        String norm = TextNormalizer.normalize(description);
        if (norm.isEmpty()) {
            return Optional.empty();
        }
        for (CategoryRule rule : rules) {
            String keyword = TextNormalizer.normalize(rule.getKeyword());
            if (!keyword.isEmpty() && norm.contains(keyword) && rule.getCategory().getType() == type
                && !rule.getCategory().isArchived()) {
                return Optional.of(rule.getCategory());
            }
        }
        return Optional.empty();
    }
}
