package br.com.contaslt.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import br.com.contaslt.category.Category;
import br.com.contaslt.common.TransactionType;
import br.com.contaslt.transaction.Transaction;

class CategorySuggesterTest {

    private final Category cartao = category("Cartão de crédito");
    private final Category impostos = category("Impostos");
    private final Category mercado = category("Mercado");
    private final Category transporte = category("Transporte");

    @Test
    void genericBankWordsDoNotVote() {
        // Cenário real: só havia "Pagamento de fatura" no histórico e o DAS saía como cartão de crédito.
        CategorySuggester s = suggester(List.of(),
            tx("Pagamento de fatura", cartao), tx("Pagamento de fatura", cartao));

        assertThat(s.suggest("Pagamento de boleto efetuado - DAS-SIMPLES NACIONAL", TransactionType.EXPENSE, null))
            .isEmpty();
        assertThat(s.suggest("Pagamento de fatura", TransactionType.EXPENSE, null)).contains(cartao);
    }

    @Test
    void specificWordFromHistoryStillSuggests() {
        CategorySuggester s = suggester(List.of(),
            tx("Pagamento de boleto - DAS SIMPLES NACIONAL 09/2026", impostos),
            tx("UBER *TRIP HELP.UBER.COM", transporte));

        assertThat(s.suggest("Pagamento de boleto efetuado - DAS-SIMPLES NACIONAL", TransactionType.EXPENSE, null))
            .contains(impostos);
        assertThat(s.suggest("Uber viagem", TransactionType.EXPENSE, null)).contains(transporte);
    }

    @Test
    void ambiguousWordsDoNotSuggest() {
        // "supermercado" já apareceu em duas categorias com o mesmo peso: não dá para escolher.
        CategorySuggester s = suggester(List.of(),
            tx("Supermercado Bom Preço", mercado), tx("Supermercado Bom Preço farmácia", transporte));

        assertThat(s.suggest("Supermercado Novo", TransactionType.EXPENSE, null)).isEmpty();
    }

    @Test
    void ruleWinsOverHistory() {
        CategoryRule rule = new CategoryRule();
        rule.setKeyword("pagamento de fatura");
        rule.setCategory(cartao);
        CategorySuggester s = suggester(List.of(rule), tx("Pagamento de fatura Nubank", impostos));

        assertThat(s.suggest("PAGAMENTO DE FATURA NUBANK", TransactionType.EXPENSE, null)).contains(cartao);
    }

    @Test
    void ignoresCategoriesOfAnotherType() {
        CategorySuggester s = suggester(List.of(), tx("Nubank rendimento", impostos));
        Optional<Category> income = s.suggest("Nubank rendimento", TransactionType.INCOME, null);
        assertThat(income).isEmpty();
    }

    private CategorySuggester suggester(List<CategoryRule> rules, Transaction... history) {
        return new CategorySuggester(List.of(cartao, impostos, mercado, transporte), rules, List.of(history));
    }

    private static Category category(String name) {
        Category c = new Category();
        c.setName(name);
        c.setType(TransactionType.EXPENSE);
        return c;
    }

    private static Transaction tx(String description, Category category) {
        Transaction t = new Transaction();
        t.setDescription(description);
        t.setType(TransactionType.EXPENSE);
        t.setCategory(category);
        return t;
    }
}
