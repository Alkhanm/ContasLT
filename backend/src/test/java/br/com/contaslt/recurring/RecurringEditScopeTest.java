package br.com.contaslt.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
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

/** Edição de uma ocorrência de conta recorrente: só ela, ou ela e os próximos vencimentos. */
@SpringBootTest
@AutoConfigureMockMvc
class RecurringEditScopeTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ObjectMapper json;

    private String token;
    private long accountId;

    @BeforeEach
    void setUp() {
        String email = "scope" + System.nanoTime() + "@exemplo.com";
        token = body(mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Scope", "email", email, "password", "segredo1")))
            .exchange()).get("token").asString();
        accountId = body(mvc.get().uri("/api/accounts").headers(auth()).exchange()).get(0).get("id").asLong();
    }

    @Test
    void movingOneOccurrenceDoesNotRecreateTheOriginalDate() {
        bill("Internet", 100, "2033-03-20", null);
        JsonNode april = items("2033-04");
        assertThat(april).hasSize(1);
        long id = april.get(0).get("id").asLong();

        assertThat(edit(id, "Internet", 100, "2033-04-01", "THIS")).hasStatusOk();

        // Antes, o gerador recriava o dia 20 e a segunda tentativa de mover dava erro de chave duplicada.
        april = items("2033-04");
        assertThat(april).hasSize(1);
        assertThat(april.get(0).get("dueDate").asString()).isEqualTo("2033-04-01");
        assertThat(edit(id, "Internet", 100, "2033-04-02", "THIS")).hasStatusOk();
        assertThat(items("2033-04")).hasSize(1);

        // Os outros meses continuam no dia 20.
        assertThat(items("2033-05").get(0).get("dueDate").asString()).isEqualTo("2033-05-20");
    }

    @Test
    void amountChangeAppliesToThisAndFollowingOnly() {
        long billId = bill("Academia", 100, "2033-03-20", null);
        items("2033-03");
        items("2033-04");
        long mayId = items("2033-05").get(0).get("id").asLong();

        assertThat(edit(mayId, "Academia Plus", 150, "2033-05-20", "FOLLOWING")).hasStatusOk();

        assertThat(items("2033-04").get(0).get("amount").decimalValue()).isEqualByComparingTo("100");
        assertThat(items("2033-05").get(0).get("amount").decimalValue()).isEqualByComparingTo("150");
        JsonNode june = items("2033-06").get(0);
        assertThat(june.get("amount").decimalValue()).isEqualByComparingTo("150");
        assertThat(june.get("description").asString()).isEqualTo("Academia Plus");
        assertThat(june.get("dueDate").asString()).isEqualTo("2033-06-20");

        JsonNode b = billById(billId);
        assertThat(b.get("amount").decimalValue()).isEqualByComparingTo("150");
        assertThat(b.get("description").asString()).isEqualTo("Academia Plus");
    }

    @Test
    void dateChangeReanchorsFollowingOccurrences() {
        bill("Aluguel", 1500, "2033-03-20", null);
        items("2033-05");
        long juneId = items("2033-06").get(0).get("id").asLong();
        items("2033-07");

        assertThat(edit(juneId, "Aluguel", 1500, "2033-06-01", "FOLLOWING")).hasStatusOk();

        assertThat(items("2033-05").get(0).get("dueDate").asString()).isEqualTo("2033-05-20");
        JsonNode june = items("2033-06");
        assertThat(june).hasSize(1);
        assertThat(june.get(0).get("id").asLong()).isEqualTo(juneId);
        assertThat(june.get(0).get("dueDate").asString()).isEqualTo("2033-06-01");
        JsonNode july = items("2033-07");
        assertThat(july).hasSize(1);
        assertThat(july.get(0).get("dueDate").asString()).isEqualTo("2033-07-01");
        assertThat(items("2033-08").get(0).get("dueDate").asString()).isEqualTo("2033-08-01");
    }

    @Test
    void reanchoringInstallmentsKeepsNumberingAndTotal() {
        bill("Geladeira", 300, "2034-01-10", 6);
        items("2034-01");
        items("2034-02");
        JsonNode third = items("2034-03").get(0);
        assertThat(third.get("installmentNumber").asInt()).isEqualTo(3);

        assertThat(edit(third.get("id").asLong(), "Geladeira", 300, "2034-03-01", "FOLLOWING")).hasStatusOk();

        JsonNode april = items("2034-04").get(0);
        assertThat(april.get("dueDate").asString()).isEqualTo("2034-04-01");
        assertThat(april.get("installmentNumber").asInt()).isEqualTo(4);
        assertThat(april.get("installmentTotal").asInt()).isEqualTo(6);
        assertThat(items("2034-06").get(0).get("installmentNumber").asInt()).isEqualTo(6);
        assertThat(items("2034-07")).isEmpty();
        assertThat(items("2034-02").get(0).get("dueDate").asString()).isEqualTo("2034-02-10");
    }

    private long bill(String description, double amount, String startDate, Integer occurrences) {
        Map<String, Object> m = new HashMap<>();
        m.put("description", description);
        m.put("amount", amount);
        m.put("type", "EXPENSE");
        m.put("frequency", "MONTHLY");
        m.put("startDate", startDate);
        m.put("occurrences", occurrences);
        m.put("accountId", accountId);
        MvcTestResult res = mvc.post().uri("/api/recurring-bills").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(m)).exchange();
        assertThat(res).hasStatus(201);
        return body(res).get("id").asLong();
    }

    private MvcTestResult edit(long id, String description, double amount, String dueDate, String scope) {
        Map<String, Object> m = new HashMap<>();
        m.put("description", description);
        m.put("amount", amount);
        m.put("type", "EXPENSE");
        m.put("dueDate", dueDate);
        m.put("paid", false);
        m.put("accountId", accountId);
        return mvc.put().uri("/api/transactions/" + id + "?scope=" + scope).headers(auth())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(m)).exchange();
    }

    private JsonNode items(String month) {
        return body(mvc.get().uri("/api/transactions?month=" + month).headers(auth()).exchange()).get("items");
    }

    private JsonNode billById(long id) {
        for (JsonNode b : body(mvc.get().uri("/api/recurring-bills").headers(auth()).exchange())) {
            if (b.get("id").asLong() == id) {
                return b;
            }
        }
        throw new AssertionError("conta recorrente não encontrada: " + id);
    }

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
}
