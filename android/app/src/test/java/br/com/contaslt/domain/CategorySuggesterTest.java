package br.com.contaslt.domain;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import br.com.contaslt.data.schema.CategoryRec;
import br.com.contaslt.data.schema.CategoryRuleRec;

/** Porte de frontend/src/domain/categorySuggester.test.ts. */
public class CategorySuggesterTest {

    private static int seq;

    private static CategoryRec category(String name, String type, boolean archived) {
        CategoryRec c = new CategoryRec();
        c.id = "c" + (++seq);
        c.name = name;
        c.type = type;
        c.color = "#000000";
        c.icon = "tag";
        c.archived = archived;
        c.createdAt = "";
        return c;
    }

    private static CategoryRuleRec rule(String keyword, CategoryRec c) {
        CategoryRuleRec r = new CategoryRuleRec();
        r.id = "r" + (++seq);
        r.categoryId = c.id;
        r.keyword = keyword;
        r.createdAt = "";
        return r;
    }

    private final CategoryRec cartao = category("Cartão de crédito", "EXPENSE", false);
    private final CategoryRec impostos = category("Impostos", "EXPENSE", false);
    private final CategoryRec salario = category("Salário", "INCOME", false);

    @Test
    public void semRegraQueCaseNaoHaSugestao() {
        CategorySuggester s = new CategorySuggester(Arrays.asList(cartao, impostos),
            Collections.singletonList(rule("pagamento de fatura", cartao)));
        assertNull(s.suggest("Pagamento de boleto efetuado - DAS-SIMPLES NACIONAL", "EXPENSE", null));
        assertSame(cartao, s.suggest("PAGAMENTO DE FATURA NUBANK", "EXPENSE", null));
    }

    @Test
    public void regraMaisLongaVenceETipoPrecisaBater() {
        CategorySuggester s = new CategorySuggester(Arrays.asList(cartao, impostos, salario),
            Arrays.asList(rule("das", impostos), rule("pagamento de fatura", cartao), rule("acme", salario)));
        assertSame(cartao, s.suggest("Pagamento de fatura das compras", "EXPENSE", null));
        assertNull(s.suggest("Salario ACME", "EXPENSE", null));
        assertSame(salario, s.suggest("Salario ACME", "INCOME", null));
    }

    @Test
    public void nomeDeCategoriaDoArquivoTemPreferencia() {
        CategorySuggester s = new CategorySuggester(Arrays.asList(cartao, impostos),
            Collections.singletonList(rule("fatura", cartao)));
        assertSame(impostos, s.suggest("Fatura", "EXPENSE", "impostos"));
        assertSame(cartao, s.suggest("Fatura", "EXPENSE", "inexistente"));
    }

    @Test
    public void categoriaArquivadaNuncaESugerida() {
        CategoryRec old = category("Antiga", "EXPENSE", true);
        CategorySuggester s = new CategorySuggester(Collections.singletonList(old),
            Collections.singletonList(rule("uber", old)));
        assertNull(s.suggest("Uber viagem", "EXPENSE", null));
    }
}
