package br.com.contaslt.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Relatórios: gastos por categoria (com subcategorias e histórico) e gasto por item de comprovante. */
@SpringBootTest
@AutoConfigureMockMvc
class ReportTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ObjectMapper json;

    private String token;
    private long accountId;

    private void register() {
        token = body(mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Relatório", "email", "rel" + System.nanoTime() + "@exemplo.com",
                "password", "segredo1"))).exchange()).get("token").asString();
        accountId = body(mvc.get().uri("/api/accounts").headers(auth()).exchange()).get(0).get("id").asLong();
    }

    @Test
    void categoriesGroupSubcategoriesUnderTheParentWithPreviousMonthAndHistory() {
        register();
        long mercado = category("Mercado", null);
        long padaria = category("Padaria", mercado);
        long lazer = category("Lazer", null);

        expense("Compra do mês", 300, "2034-05-02", mercado, List.of());
        expense("Pão", 50, "2034-05-10", padaria, List.of());
        expense("Cinema", 100, "2034-05-15", lazer, List.of());
        expense("Sem rumo", 40, "2034-05-20", null, List.of());
        expense("Mercado de abril", 200, "2034-04-05", mercado, List.of());
        expense("Mercado de janeiro", 120, "2034-01-05", mercado, List.of());
        income("Salário", 5000, "2034-05-05");

        JsonNode report = body(mvc.get().uri("/api/reports/categories?month=2034-05").headers(auth()).exchange());
        assertThat(report.get("total").asDouble()).isEqualTo(490);
        assertThat(report.get("previousTotal").asDouble()).isEqualTo(200);
        assertThat(report.get("count").asInt()).isEqualTo(4);
        JsonNode items = report.get("items");
        assertThat(items).hasSize(3);

        JsonNode first = items.get(0);
        assertThat(first.get("name").asString()).isEqualTo("Mercado");
        assertThat(first.get("total").asDouble()).isEqualTo(350);
        assertThat(first.get("count").asInt()).isEqualTo(2);
        assertThat(first.get("previousTotal").asDouble()).isEqualTo(200);
        assertThat(first.get("percent").asDouble()).isEqualTo(71.4);
        // Subcategoria com gasto: Padaria e "Geral" (o que foi lançado direto em Mercado).
        assertThat(first.get("children")).hasSize(2);
        assertThat(first.get("children").get(0).get("name").asString()).isEqualTo("Geral");
        assertThat(first.get("children").get(0).get("categoryId").asLong()).isEqualTo(mercado);
        assertThat(first.get("children").get(0).get("total").asDouble()).isEqualTo(300);
        assertThat(first.get("children").get(1).get("name").asString()).isEqualTo("Padaria");
        assertThat(first.get("children").get(1).get("total").asDouble()).isEqualTo(50);
        // Seis meses: dezembro de 2033 a maio de 2034.
        JsonNode history = first.get("history");
        assertThat(history).hasSize(6);
        assertThat(history.get(0).get("month").asString()).isEqualTo("2033-12");
        assertThat(history.get(1).get("total").asDouble()).isEqualTo(120);
        assertThat(history.get(4).get("total").asDouble()).isEqualTo(200);
        assertThat(history.get(5).get("total").asDouble()).isEqualTo(350);

        assertThat(items.get(1).get("name").asString()).isEqualTo("Lazer");
        assertThat(items.get(1).get("children")).isEmpty(); // sem subcategoria: não desdobra
        assertThat(items.get(2).get("name").asString()).isEqualTo("Sem categoria");
        assertThat(items.get(2).get("categoryId").isNull()).isTrue();
    }

    @Test
    void itemsGroupReceiptLinesByNameWithMonthsAndPriceHistory() {
        register();
        long mercado = category("Mercado", null);
        long padaria = category("Padaria", mercado);

        expense("Supermercado", 20, "2034-02-03", mercado, List.of(
            item("Leite Integral 1L", 2, 4.50, 9.00),
            item("Arroz 5kg", 1, null, 11.00)));
        expense("Supermercado", 25.4, "2034-03-07", padaria, List.of(
            item("LEITE INTEGRAL 1L", 3, 5.20, 15.60),
            item("Pão francês", 0.5, 19.60, 9.80)));
        expense("Fora do período", 9, "2033-06-01", mercado, List.of(item("Leite integral 1l", 1, 3.90, 3.90)));

        JsonNode report = body(mvc.get().uri("/api/reports/items?from=2034-01&to=2034-03").headers(auth()).exchange());
        assertThat(report.get("total").asDouble()).isEqualTo(45.4);
        assertThat(report.get("count").asInt()).isEqualTo(4);
        JsonNode items = report.get("items");
        assertThat(items).hasSize(3);

        JsonNode leite = items.get(0);
        assertThat(leite.get("key").asString()).isEqualTo("leite integral l");
        assertThat(leite.get("description").asString()).isEqualTo("LEITE INTEGRAL 1L"); // nome da compra mais recente
        assertThat(leite.get("categoryName").asString()).isEqualTo("Mercado › Padaria"); // categoria da compra mais recente
        assertThat(leite.get("categoryId").asLong()).isEqualTo(padaria);
        assertThat(leite.get("count").asInt()).isEqualTo(2);
        assertThat(leite.get("quantity").asDouble()).isEqualTo(5);
        assertThat(leite.get("total").asDouble()).isEqualTo(24.6);
        assertThat(leite.get("firstUnitPrice").asDouble()).isEqualTo(4.5);
        assertThat(leite.get("lastUnitPrice").asDouble()).isEqualTo(5.2);
        assertThat(leite.get("minUnitPrice").asDouble()).isEqualTo(4.5);
        assertThat(leite.get("maxUnitPrice").asDouble()).isEqualTo(5.2);
        assertThat(leite.get("months")).hasSize(2);
        assertThat(leite.get("months").get(0).get("month").asString()).isEqualTo("2034-02");
        assertThat(leite.get("months").get(1).get("total").asDouble()).isEqualTo(15.6);
        assertThat(leite.get("purchases")).hasSize(2);
        assertThat(leite.get("purchases").get(0).get("date").asString()).isEqualTo("2034-02-03");

        // Sem preço unitário: total ÷ quantidade.
        JsonNode arroz = items.get(1);
        assertThat(arroz.get("description").asString()).isEqualTo("Arroz 5kg");
        assertThat(arroz.get("lastUnitPrice").asDouble()).isEqualTo(11);

        // Filtro pela categoria pai inclui a subcategoria; pela subcategoria, só ela. Busca pelo nome.
        JsonNode byParent = body(mvc.get().uri("/api/reports/items?from=2034-01&to=2034-03&categoryId=" + mercado).headers(auth()).exchange());
        assertThat(byParent.get("items")).hasSize(3);
        JsonNode bySub = body(mvc.get().uri("/api/reports/items?from=2034-01&to=2034-03&categoryId=" + padaria).headers(auth()).exchange());
        assertThat(bySub.get("items")).hasSize(2);
        assertThat(bySub.get("items").get(0).get("count").asInt()).isEqualTo(1);
        JsonNode bySearch = body(mvc.get().uri("/api/reports/items?from=2034-01&to=2034-03&search={s}", "pão").headers(auth()).exchange());
        assertThat(bySearch.get("items")).hasSize(1);
        assertThat(bySearch.get("items").get(0).get("description").asString()).isEqualTo("Pão francês");

        assertThat(mvc.get().uri("/api/reports/items?from=2034-04&to=2034-03").headers(auth()).exchange()).hasStatus(422);
    }

    private long category(String name, Long parentId) {
        Map<String, Object> req = new HashMap<>();
        req.put("name", name);
        req.put("type", "EXPENSE");
        req.put("color", "#3366FF");
        req.put("icon", "tag");
        req.put("parentId", parentId);
        return body(mvc.post().uri("/api/categories").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(req)).exchange()).get("id").asLong();
    }

    private static Map<String, Object> item(String description, double quantity, Double unitPrice, double total) {
        Map<String, Object> i = new HashMap<>();
        i.put("description", description);
        i.put("quantity", quantity);
        i.put("unitPrice", unitPrice);
        i.put("total", total);
        return i;
    }

    private void expense(String description, double amount, String date, Long categoryId, List<Map<String, Object>> items) {
        Map<String, Object> req = new HashMap<>();
        req.put("description", description);
        req.put("amount", amount);
        req.put("type", "EXPENSE");
        req.put("dueDate", date);
        req.put("paid", true);
        req.put("accountId", accountId);
        req.put("categoryId", categoryId);
        req.put("items", items);
        if (!items.isEmpty()) {
            req.put("merchant", "Mercadinho");
        }
        MvcTestResult res = mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(req)).exchange();
        assertThat(res).hasStatus(201);
    }

    private void income(String description, double amount, String date) {
        Map<String, Object> req = new HashMap<>();
        req.put("description", description);
        req.put("amount", amount);
        req.put("type", "INCOME");
        req.put("dueDate", date);
        req.put("paid", true);
        req.put("accountId", accountId);
        assertThat(mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(req)).exchange()).hasStatus(201);
    }

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private JsonNode body(MvcTestResult result) {
        try {
            return json.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
