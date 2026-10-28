package br.com.contaslt.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.contaslt.category.Category;
import br.com.contaslt.common.TransactionType;

class CategorySuggesterTest {

    private final Category cartao = category("Cartão de crédito", TransactionType.EXPENSE);
    private final Category impostos = category("Impostos", TransactionType.EXPENSE);
    private final Category salario = category("Salário", TransactionType.INCOME);

    @Test
    void withoutMatchingRuleThereIsNoSuggestion() {
        CategorySuggester s = new CategorySuggester(List.of(cartao, impostos), List.of(rule("pagamento de fatura", cartao)));

        assertThat(s.suggest("Pagamento de boleto efetuado - DAS-SIMPLES NACIONAL", TransactionType.EXPENSE, null))
            .isEmpty();
        assertThat(s.suggest("PAGAMENTO DE FATURA NUBANK", TransactionType.EXPENSE, null)).contains(cartao);
    }

    @Test
    void longestRuleWinsAndTypeMustMatch() {
        CategorySuggester s = new CategorySuggester(List.of(cartao, impostos, salario),
            List.of(rule("das", impostos), rule("pagamento de fatura", cartao), rule("acme", salario)));

        assertThat(s.suggest("Pagamento de fatura das compras", TransactionType.EXPENSE, null)).contains(cartao);
        assertThat(s.suggest("Salario ACME", TransactionType.EXPENSE, null)).isEmpty();
        assertThat(s.suggest("Salario ACME", TransactionType.INCOME, null)).contains(salario);
    }

    @Test
    void categoryNameFromFileComesFirst() {
        CategorySuggester s = new CategorySuggester(List.of(cartao, impostos), List.of(rule("fatura", cartao)));

        assertThat(s.suggest("Fatura", TransactionType.EXPENSE, "impostos")).contains(impostos);
        assertThat(s.suggest("Fatura", TransactionType.EXPENSE, "inexistente")).contains(cartao);
    }

    @Test
    void archivedCategoryIsNeverSuggested() {
        Category old = category("Antiga", TransactionType.EXPENSE);
        old.setArchived(true);
        CategorySuggester s = new CategorySuggester(List.of(old), List.of(rule("uber", old)));

        assertThat(s.suggest("Uber viagem", TransactionType.EXPENSE, null)).isEmpty();
    }

    private static CategoryRule rule(String keyword, Category category) {
        CategoryRule r = new CategoryRule();
        r.setKeyword(keyword);
        r.setCategory(category);
        return r;
    }

    private static Category category(String name, TransactionType type) {
        Category c = new Category();
        c.setName(name);
        c.setType(type);
        return c;
    }
}
