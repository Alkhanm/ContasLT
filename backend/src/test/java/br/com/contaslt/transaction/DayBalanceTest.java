package br.com.contaslt.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
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

/** Saldo ao fim de cada dia e saldo anterior/atual do mês na lista de lançamentos. */
@SpringBootTest
@AutoConfigureMockMvc
class DayBalanceTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ObjectMapper json;

    private String token;

    @Test
    void runningBalanceCountsOnlyPaidAndCarriesOverDays() {
        token = body(mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Saldo", "email", "saldo" + System.nanoTime() + "@exemplo.com",
                "password", "segredo1"))).exchange()).get("token").asString();
        JsonNode accounts = body(mvc.get().uri("/api/accounts").headers(auth()).exchange());
        long accountId = accounts.get(0).get("id").asLong();
        double initial = accounts.get(0).get("initialBalance").asDouble();

        transaction(accountId, "Anterior", 100, "INCOME", "2032-01-20", true);   // mês anterior: entra no ponto de partida
        transaction(accountId, "Salário", 1000, "INCOME", "2032-02-05", true);
        transaction(accountId, "Luz", 200, "EXPENSE", "2032-02-05", true);
        transaction(accountId, "Boleto a pagar", 999, "EXPENSE", "2032-02-10", false); // pendente: não conta
        transaction(accountId, "Mercado", 50, "EXPENSE", "2032-02-15", true);

        JsonNode days = body(mvc.get().uri("/api/transactions?month=2032-02").headers(auth()).exchange()).get("dayBalances");
        assertThat(days).hasSize(29); // fevereiro de 2032 é bissexto
        assertThat(at(days, "2032-02-01")).isEqualTo(initial + 100);
        assertThat(at(days, "2032-02-05")).isEqualTo(initial + 100 + 1000 - 200);
        assertThat(at(days, "2032-02-10")).isEqualTo(initial + 900);
        assertThat(at(days, "2032-02-15")).isEqualTo(initial + 850);
        assertThat(at(days, "2032-02-29")).isEqualTo(initial + 850);
    }

    @Test
    void previousBalanceCarriesResultOfEarlierMonthsIntoCurrentBalance() {
        token = body(mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Saldo", "email", "saldo" + System.nanoTime() + "@exemplo.com",
                "password", "segredo1"))).exchange()).get("token").asString();
        long accountId = body(mvc.get().uri("/api/accounts").headers(auth()).exchange()).get(0).get("id").asLong();

        transaction(accountId, "Salário jan", 1000, "INCOME", "2033-01-05", true);
        transaction(accountId, "Aluguel jan", 700, "EXPENSE", "2033-01-10", true);
        transaction(accountId, "Salário fev", 1000, "INCOME", "2033-02-05", true);
        transaction(accountId, "Aluguel fev", 700, "EXPENSE", "2033-02-10", false); // pendente também conta no resultado
        transaction(accountId, "Mercado mar", 100, "EXPENSE", "2033-03-02", true);

        JsonNode jan = body(mvc.get().uri("/api/transactions?month=2033-01").headers(auth()).exchange()).get("summary");
        assertThat(jan.get("previousBalance").asDouble()).isEqualTo(0);
        assertThat(jan.get("balance").asDouble()).isEqualTo(300);
        assertThat(jan.get("currentBalance").asDouble()).isEqualTo(300);

        JsonNode feb = body(mvc.get().uri("/api/transactions?month=2033-02").headers(auth()).exchange()).get("summary");
        assertThat(feb.get("previousBalance").asDouble()).isEqualTo(300);
        assertThat(feb.get("currentBalance").asDouble()).isEqualTo(600);

        // Março: saldo anterior acumula janeiro e fevereiro; o filtro de situação vale também para o anterior.
        JsonNode mar = body(mvc.get().uri("/api/transactions?month=2033-03").headers(auth()).exchange()).get("summary");
        assertThat(mar.get("previousBalance").asDouble()).isEqualTo(600);
        assertThat(mar.get("currentBalance").asDouble()).isEqualTo(500);
        JsonNode marPaid = body(mvc.get().uri("/api/transactions?month=2033-03&paid=true").headers(auth()).exchange()).get("summary");
        assertThat(marPaid.get("previousBalance").asDouble()).isEqualTo(1300);
        assertThat(marPaid.get("currentBalance").asDouble()).isEqualTo(1200);
    }

    private static double at(JsonNode days, String date) {
        for (JsonNode d : days) {
            if (d.get("date").asString().equals(date)) {
                return d.get("balance").asDouble();
            }
        }
        throw new AssertionError("sem saldo para " + date);
    }

    private void transaction(long accountId, String description, int amount, String type, String dueDate, boolean paid) {
        Map<String, Object> m = new HashMap<>();
        m.put("description", description);
        m.put("amount", amount);
        m.put("type", type);
        m.put("dueDate", dueDate);
        m.put("paid", paid);
        m.put("accountId", accountId);
        assertThat(mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(m)).exchange()).hasStatus(201);
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
