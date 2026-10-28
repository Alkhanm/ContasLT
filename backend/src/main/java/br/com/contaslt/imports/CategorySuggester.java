package br.com.contaslt.imports;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import br.com.contaslt.category.Category;
import br.com.contaslt.common.TextNormalizer;
import br.com.contaslt.common.TransactionType;
import br.com.contaslt.transaction.Transaction;

/**
 * Sugere categoria para uma descrição: 1) nome de categoria vindo do arquivo, 2) regras do usuário,
 * 3) histórico de lançamentos já categorizados (descrição idêntica, depois por palavras).
 */
public class CategorySuggester {

    /**
     * Palavras que descrevem a operação bancária, não quem recebeu o dinheiro. Aparecem em lançamentos de
     * qualquer categoria ("Pagamento de fatura", "Pagamento de boleto DAS"), então não contam como voto.
     */
    static final Set<String> GENERIC_WORDS = Set.of(
        "pagamento", "pagamentos", "pagto", "pgto", "compra", "compras", "debito", "credito", "cartao",
        "transferencia", "transf", "enviada", "enviado", "recebida", "recebido", "boleto", "titulo", "cobranca",
        "efetuado", "efetuada", "autorizado", "autorizada", "aprovado", "aprovada", "agendado", "agendada",
        "automatico", "automatica", "saque", "deposito", "tarifa", "parcela", "parcelas", "lancamento", "conta",
        "online", "internet", "mobile", "para", "ltda", "eireli");

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
                if (isSignificant(token)) {
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
        return byTokens(norm, type);
    }

    /**
     * Voto ponderado por palavra: cada palavra significativa da descrição vota nas categorias em que já apareceu.
     * Uma palavra que aparece em várias categorias vale menos (peso 1/n categorias). Só sugere quando a categoria
     * vencedora tem mais da metade dos votos, para não chutar por uma palavra ambígua.
     */
    private Optional<Category> byTokens(String norm, TransactionType type) {
        Map<Category, Double> votes = new LinkedHashMap<>();
        for (String token : new HashSet<>(List.of(norm.split(" ")))) {
            Map<Category, Integer> counts = tokenHistory.get(token);
            if (counts == null) {
                continue;
            }
            double weight = 1.0 / counts.size();
            counts.forEach((c, n) -> votes.merge(c, n * weight, Double::sum));
        }
        double total = votes.values().stream().mapToDouble(Double::doubleValue).sum();
        return votes.entrySet().stream()
            .filter(e -> e.getKey().getType() == type && !e.getKey().isArchived())
            .max(Map.Entry.comparingByValue())
            .filter(e -> e.getValue() > total / 2)
            .map(Map.Entry::getKey);
    }

    static boolean isSignificant(String token) {
        return token.length() >= 4 && !GENERIC_WORDS.contains(token);
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
