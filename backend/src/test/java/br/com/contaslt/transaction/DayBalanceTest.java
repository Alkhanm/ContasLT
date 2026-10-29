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
    void summaryFollowsTheStatementWithTransfersAndOnlyWhatIsPaid() {
        token = body(mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Saldo", "email", "saldo" + System.nanoTime() + "@exemplo.com",
                "password", "segredo1"))).exchange()).get("token").asString();
        long pfId = body(mvc.get().uri("/api/accounts").headers(auth()).exchange()).get(0).get("id").asLong();
        long pjId = body(mvc.post().uri("/api/accounts").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "PJ", "type", "CHECKING", "initialBalance", 500, "color", "#1B5E3F")))
            .exchange()).get("id").asLong();

        transaction(pjId, "Cliente jan", 1000, "INCOME", "2033-01-05", true);
        transaction(pfId, "Mercado jan", 200, "EXPENSE", "2033-01-10", true);
        // Fevereiro: pró-labore PJ → PF (transferência), uma despesa paga e uma pendente na PF.
        assertThat(mvc.post().uri("/api/transfers").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("fromAccountId", pjId, "toAccountId", pfId, "amount", 800,
                "date", "2033-02-05", "description", "Pró-labore"))).exchange()).hasStatus(201);
        transaction(pfId, "Aluguel fev", 700, "EXPENSE", "2033-02-10", true);
        transaction(pfId, "Boleto fev", 50, "EXPENSE", "2033-02-20", false);

        // PF em fevereiro: anterior + transferência + receitas − despesas = saldo; só o pago, o pendente vai para o previsto.
        JsonNode page = body(mvc.get().uri("/api/transactions?month=2033-02&accountId=" + pfId).headers(auth()).exchange());
        JsonNode pf = page.get("summary");
        assertThat(pf.get("previousBalance").asDouble()).isEqualTo(-200);
        assertThat(pf.get("transfer").asDouble()).isEqualTo(800);
        assertThat(pf.get("income").asDouble()).isEqualTo(0);
        assertThat(pf.get("expense").asDouble()).isEqualTo(700);
        assertThat(pf.get("balance").asDouble()).isEqualTo(-100);
        assertThat(pf.get("pendingExpense").asDouble()).isEqualTo(50);
        assertThat(pf.get("projectedBalance").asDouble()).isEqualTo(-150);
        assertThat(pf.get("count").asInt()).isEqualTo(3);
        // O saldo ao fim do mês no cabeçalho do dia é o mesmo número.
        assertThat(at(page.get("dayBalances"), "2033-02-28")).isEqualTo(-100);

        // O resumo é da conta, não da lista filtrada: filtrando "pendentes", só a contagem muda.
        JsonNode pending = body(mvc.get().uri("/api/transactions?month=2033-02&accountId=" + pfId + "&paid=false").headers(auth()).exchange()).get("summary");
        assertThat(pending.get("balance").asDouble()).isEqualTo(-100);
        assertThat(pending.get("expense").asDouble()).isEqualTo(700);
        assertThat(pending.get("count").asInt()).isEqualTo(1);

        // Todas as contas: as duas pernas da transferência se anulam; o anterior inclui o saldo inicial da PJ.
        JsonNode all = body(mvc.get().uri("/api/transactions?month=2033-02").headers(auth()).exchange()).get("summary");
        assertThat(all.get("previousBalance").asDouble()).isEqualTo(500 + 1000 - 200);
        assertThat(all.get("transfer").asDouble()).isEqualTo(0);
        assertThat(all.get("expense").asDouble()).isEqualTo(700);
        assertThat(all.get("balance").asDouble()).isEqualTo(600);
        assertThat(all.get("projectedBalance").asDouble()).isEqualTo(550);
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
