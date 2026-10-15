package br.com.contaslt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiFlowTest {

    private static String token;
    private static long accountId;
    private static long expenseCategoryId;
    private static long incomeCategoryId;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ObjectMapper json;

    private JsonNode body(MvcTestResult result) {
        try {
            return json.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    @Test
    @Order(1)
    void registerSeedsDefaultsAndReturnsToken() {
        MvcTestResult res = mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Ana", "email", "ana@exemplo.com", "password", "segredo1")))
            .exchange();
        assertThat(res).hasStatus(201);
        token = body(res).get("token").asString();
        assertThat(token).isNotBlank();

        MvcTestResult accounts = mvc.get().uri("/api/accounts").headers(auth()).exchange();
        assertThat(accounts).hasStatusOk();
        JsonNode list = body(accounts);
        assertThat(list).hasSize(1);
        accountId = list.get(0).get("id").asLong();

        JsonNode categories = body(mvc.get().uri("/api/categories").headers(auth()).exchange());
        assertThat(categories.size()).isGreaterThan(10);
        for (JsonNode c : categories) {
            if (expenseCategoryId == 0 && c.get("type").asString().equals("EXPENSE")) {
                expenseCategoryId = c.get("id").asLong();
            }
            if (incomeCategoryId == 0 && c.get("type").asString().equals("INCOME")) {
                incomeCategoryId = c.get("id").asLong();
            }
        }
    }

    @Test
    @Order(2)
    void corsPreflightAllowsAnyLocalPort() {
        MvcTestResult res = mvc.options().uri("/api/accounts")
            .header("Origin", "http://localhost:4173")
            .header("Access-Control-Request-Method", "GET")
            .header("Access-Control-Request-Headers", "authorization")
            .exchange();
        assertThat(res).hasStatusOk();
        assertThat(res.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:4173");

        MvcTestResult denied = mvc.options().uri("/api/accounts")
            .header("Origin", "http://evil.example.com")
            .header("Access-Control-Request-Method", "GET")
            .exchange();
        assertThat(denied).hasStatus(403);
    }

    @Test
    @Order(2)
    void unauthenticatedIsRejected() {
        assertThat(mvc.get().uri("/api/accounts").exchange()).hasStatus(401);
        MvcTestResult bad = mvc.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", "ana@exemplo.com", "password", "errada"))).exchange();
        assertThat(bad).hasStatus(401);
    }

    @Test
    @Order(3)
    void recurringBillMaterializesIntoMonthlyTransactions() {
        LocalDate start = YearMonth.now().atDay(10);
        MvcTestResult created = mvc.post().uri("/api/recurring-bills").headers(auth())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of(
                "description", "Aluguel", "amount", 1500, "type", "EXPENSE", "frequency", "MONTHLY",
                "startDate", start.toString(), "accountId", accountId, "categoryId", expenseCategoryId)))
            .exchange();
        assertThat(created).hasStatus(201);
        long billId = body(created).get("id").asLong();

        JsonNode page = body(mvc.get().uri("/api/transactions?month=" + YearMonth.now()).headers(auth()).exchange());
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("recurringBillId").asLong()).isEqualTo(billId);
        assertThat(page.get("summary").get("expense").decimalValue()).isEqualByComparingTo("1500");

        // Próximo mês também gera a ocorrência, de forma idempotente.
        String next = YearMonth.now().plusMonths(1).toString();
        assertThat(body(mvc.get().uri("/api/transactions?month=" + next).headers(auth()).exchange()).get("items")).hasSize(1);
        assertThat(body(mvc.get().uri("/api/transactions?month=" + next).headers(auth()).exchange()).get("items")).hasSize(1);

        // Alterar o valor atualiza as ocorrências pendentes.
        MvcTestResult updated = mvc.put().uri("/api/recurring-bills/" + billId).headers(auth())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of(
                "description", "Aluguel", "amount", 1600, "type", "EXPENSE", "frequency", "MONTHLY",
                "startDate", start.toString(), "accountId", accountId, "categoryId", expenseCategoryId)))
            .exchange();
        assertThat(updated).hasStatusOk();
        JsonNode after = body(mvc.get().uri("/api/transactions?month=" + YearMonth.now()).headers(auth()).exchange());
        assertThat(after.get("items").get(0).get("amount").decimalValue()).isEqualByComparingTo("1600");

        // Marcar como pago reflete no saldo da conta.
        long txId = after.get("items").get(0).get("id").asLong();
        assertThat(mvc.patch().uri("/api/transactions/" + txId + "/paid").headers(auth())
            .contentType(MediaType.APPLICATION_JSON).content("{\"paid\":true}").exchange()).hasStatusOk();
        JsonNode accounts = body(mvc.get().uri("/api/accounts").headers(auth()).exchange());
        assertThat(accounts.get(0).get("balance").decimalValue()).isEqualByComparingTo("-1600");
    }

    @Test
    @Order(4)
    void importOfxPreviewAndConfirmSkipsDuplicates() throws Exception {
        byte[] ofx = getClass().getResourceAsStream("/samples/extrato.ofx").readAllBytes();
        MockMultipartFile file = new MockMultipartFile("file", "extrato.ofx", "application/x-ofx", ofx);

        MvcTestResult preview = mvc.post().uri("/api/imports/preview").headers(auth()).multipart()
            .file(file).param("accountId", String.valueOf(accountId)).exchange();
        assertThat(preview).hasStatusOk();
        JsonNode pv = body(preview);
        assertThat(pv.get("source").asString()).isEqualTo("OFX");
        assertThat(pv.get("rows")).hasSize(3);
        assertThat(pv.get("duplicates").asInt()).isZero();

        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (JsonNode r : pv.get("rows")) {
            boolean income = r.get("type").asString().equals("INCOME");
            rows.add(Map.of(
                "date", r.get("date").asString(), "description", r.get("description").asString(),
                "amount", r.get("amount").decimalValue(), "type", r.get("type").asString(),
                "externalId", r.get("externalId").asString(),
                "categoryId", income ? incomeCategoryId : expenseCategoryId, "learnRule", true));
        }
        MvcTestResult confirm = mvc.post().uri("/api/imports/confirm").headers(auth())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("accountId", accountId, "source", "OFX", "filename", "extrato.ofx", "rows", rows)))
            .exchange();
        assertThat(confirm).hasStatus(201);
        assertThat(body(confirm).get("imported").asInt()).isEqualTo(3);

        // Segunda prévia do mesmo arquivo marca tudo como duplicado e sugere categorias aprendidas.
        JsonNode again = body(mvc.post().uri("/api/imports/preview").headers(auth()).multipart()
            .file(file).param("accountId", String.valueOf(accountId)).exchange());
        assertThat(again.get("duplicates").asInt()).isEqualTo(3);
        assertThat(again.get("rows").get(0).get("suggestedCategoryId").asLong()).isEqualTo(expenseCategoryId);

        MvcTestResult reconfirm = mvc.post().uri("/api/imports/confirm").headers(auth())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("accountId", accountId, "source", "OFX", "rows", rows)))
            .exchange();
        assertThat(body(reconfirm).get("skipped").asInt()).isEqualTo(3);
        assertThat(body(mvc.get().uri("/api/category-rules").headers(auth()).exchange())).hasSize(3);
    }

    @Test
    @Order(5)
    void dashboardAggregatesMonth() {
        MvcTestResult res = mvc.get().uri("/api/dashboard?month=2026-10").headers(auth()).exchange();
        assertThat(res).hasStatusOk();
        JsonNode d = body(res);
        assertThat(d.get("income").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(d.get("byCategory").size()).isGreaterThanOrEqualTo(1);
        assertThat(d.get("history")).hasSize(6);
        assertThat(d.get("accounts")).hasSize(1);
    }

    @Test
    @Order(6)
    void validationErrorsAreProblemDetails() {
        MvcTestResult res = mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"description\":\"\",\"amount\":-1}").exchange();
        assertThat(res).hasStatus(400);
        JsonNode pd = body(res);
        assertThat(pd.get("errors").has("description")).isTrue();
        assertThat(pd.get("errors").has("amount")).isTrue();
    }
}
