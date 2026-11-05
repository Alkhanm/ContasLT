package br.com.contaslt.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import br.com.contaslt.data.schema.CategoryRec;
import br.com.contaslt.data.schema.CategoryRuleRec;

/**
 * Sugere categoria para uma descrição (porte de frontend/src/domain/categorySuggester.ts): 1) nome de categoria vindo
 * do arquivo, 2) regras do usuário. Não adivinha pelo histórico de lançamentos: é melhor ficar sem categoria do que
 * mal categorizado.
 *
 * <p>{@code categories} na ordem em que o nome do arquivo deve ser procurado (tipo, nome); {@code rules} em qualquer
 * ordem: a palavra-chave mais longa vence (empate: ordem alfabética da palavra-chave).
 */
public class CategorySuggester {

    private static final class Rule {
        final String keyword;
        final CategoryRec category;

        Rule(String keyword, CategoryRec category) {
            this.keyword = keyword;
            this.category = category;
        }
    }

    private final List<CategoryRec> categories;
    private final List<Rule> rules = new ArrayList<>();

    public CategorySuggester(List<CategoryRec> categories, List<CategoryRuleRec> rules) {
        this.categories = new ArrayList<>(categories);
        Map<String, CategoryRec> byId = new HashMap<>();
        for (CategoryRec c : categories) {
            byId.put(c.id, c);
        }
        List<CategoryRuleRec> sorted = new ArrayList<>(rules);
        sorted.sort((a, b) -> {
            int byLength = Integer.compare(b.keyword.length(), a.keyword.length());
            return byLength != 0 ? byLength : a.keyword.compareTo(b.keyword);
        });
        for (CategoryRuleRec r : sorted) {
            CategoryRec category = byId.get(r.categoryId);
            if (category != null) {
                this.rules.add(new Rule(Text.normalize(r.keyword), category));
            }
        }
    }

    public CategoryRec suggest(String description, String type, String categoryHint) {
        if (categoryHint != null && !Text.trim(categoryHint).isEmpty()) {
            String hint = Text.normalize(categoryHint);
            for (CategoryRec c : categories) {
                if (c.type.equals(type) && Text.normalize(c.name).equals(hint)) {
                    return c;
                }
            }
        }
        String norm = Text.normalize(description);
        if (norm.isEmpty()) {
            return null;
        }
        for (Rule rule : rules) {
            if (!rule.keyword.isEmpty() && norm.contains(rule.keyword) && rule.category.type.equals(type)
                && !rule.category.archived) {
                return rule.category;
            }
        }
        return null;
    }
}
