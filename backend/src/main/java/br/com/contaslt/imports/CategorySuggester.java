package br.com.contaslt.imports;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import br.com.contaslt.category.Category;
import br.com.contaslt.common.TextNormalizer;
import br.com.contaslt.common.TransactionType;
import br.com.contaslt.transaction.Transaction;

/**
 * Sugere categoria para uma descrição: 1) nome de categoria vindo do arquivo, 2) regras do usuário,
 * 3) histórico de lançamentos já categorizados (descrição idêntica, depois por tokens).
 */
public class CategorySuggester {

    private final List<Category> categories;
    private final List<CategoryRule> rules;
    private final Map<String, Map<Category, Integer>> exactHistory = new HashMap<>();
    private final Map<String, Map<Category, Integer>> tokenHistory = new HashMap<>();

    public CategorySuggester(List<Category> categories, List<CategoryRule> rules, List<Transaction> history) {
        this.categories = categories;
        this.rules = rules.stream()
            .sorted(Comparator.comparingInt((CategoryRule r) -> r.getKeyword().length()).reversed())
            .toList();
        for (Transaction t : history) {
            if (t.getCategory() == null) {
                continue;
            }
            String norm = TextNormalizer.normalize(t.getDescription());
            if (norm.isEmpty()) {
                continue;
            }
            exactHistory.computeIfAbsent(norm, k -> new LinkedHashMap<>()).merge(t.getCategory(), 1, Integer::sum);
            for (String token : norm.split(" ")) {
                if (token.length() >= 4) {
                    tokenHistory.computeIfAbsent(token, k -> new LinkedHashMap<>()).merge(t.getCategory(), 1, Integer::sum);
                }
            }
        }
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
            if (!keyword.isEmpty() && norm.contains(keyword) && rule.getCategory().getType() == type) {
                return Optional.of(rule.getCategory());
            }
        }
        Optional<Category> exact = best(exactHistory.get(norm), type);
        if (exact.isPresent()) {
            return exact;
        }
        Map<Category, Integer> votes = new LinkedHashMap<>();
        for (String token : norm.split(" ")) {
            Map<Category, Integer> counts = tokenHistory.get(token);
            if (counts != null) {
                counts.forEach((c, n) -> votes.merge(c, n, Integer::sum));
            }
        }
        return best(votes, type);
    }

    private static Optional<Category> best(Map<Category, Integer> counts, TransactionType type) {
        if (counts == null || counts.isEmpty()) {
            return Optional.empty();
        }
        return counts.entrySet().stream()
            .filter(e -> e.getKey().getType() == type && !e.getKey().isArchived())
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey);
    }
}
