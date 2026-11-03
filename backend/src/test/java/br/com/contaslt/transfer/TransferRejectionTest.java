package br.com.contaslt.transfer;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Recusar um par no cruzamento: entre duas contas recorrentes (ex.: "Bitcoin" de 500 no dia 1 e uma transferência
 * recorrente de 500 no dia 1) basta dizer "não" uma vez e nenhum mês delas volta a ser sugerido.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransferRejectionTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void rejectingARecurringPairSilencesEveryMonth() throws Exception {
        String email = "rej-" + System.nanoTime() + "@contaslt.app";
        String token = body(mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "R", "email", email, "password", "segredo123"))).exchange()).get("token").asString();
        long main = body(mvc.get().uri("/api/accounts").headers(auth(token)).exchange()).get(0).get("id").asLong();
        long invest = body(mvc.post().uri("/api/accounts").headers(auth(token)).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Corretora", "type", "INVESTMENT", "initialBalance", 0, "color", "#123456"))).exchange()).get("id").asLong();

        // Saída recorrente de 500 na conta principal e entrada recorrente de 500 na corretora, ambas dia 1, 4 parcelas.
        for (Map<String, Object> bill : List.<Map<String, Object>>of(
                Map.of("description", "Bitcoin", "amount", 500, "type", "EXPENSE", "frequency", "MONTHLY", "startDate", "2026-01-01", "occurrences", 4, "accountId", main),
                Map.of("description", "Aporte", "amount", 500, "type", "INCOME", "frequency", "MONTHLY", "startDate", "2026-01-01", "occurrences", 4, "accountId", invest))) {
            assertThat(mvc.post().uri("/api/recurring-bills").headers(auth(token)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(bill)).exchange()).hasStatus(201);
        }
        // As ocorrências nascem quando o mês é consultado.
        for (String month : new String[] { "2026-01", "2026-02", "2026-03", "2026-04" }) {
            mvc.get().uri("/api/transactions?month=" + month).headers(auth(token)).exchange();
        }
        JsonNode before = body(mvc.get().uri("/api/transfers/suggestions").headers(auth(token)).exchange());
        assertThat(before.get("items")).hasSize(4);
        assertThat(before.get("rejected").asLong()).isZero();

        JsonNode pair = before.get("items").get(0);
        JsonNode rejected = body(mvc.post().uri("/api/transfers/reject").headers(auth(token)).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("transactionId", pair.get("from").get("id").asLong(),
                "otherTransactionId", pair.get("to").get("id").asLong()))).exchange());
        assertThat(rejected.get("recurring").asBoolean()).isTrue();

        JsonNode after = body(mvc.get().uri("/api/transfers/suggestions").headers(auth(token)).exchange());
        assertThat(after.get("items")).isEmpty();                 // os outros 3 meses sumiram junto
        assertThat(after.get("rejected").asLong()).isEqualTo(1);

        // Um lançamento avulso de 500 na corretora ainda cruza com o "Bitcoin" (a recusa foi só entre as duas regras).
        mvc.post().uri("/api/transactions").headers(auth(token)).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("description", "Pix recebido", "amount", 500, "type", "INCOME", "dueDate", "2026-02-02",
                "paid", true, "accountId", invest))).exchange();
        JsonNode loose = body(mvc.get().uri("/api/transfers/suggestions").headers(auth(token)).exchange());
        assertThat(loose.get("items")).hasSize(1);
        assertThat(loose.get("items").get(0).get("to").get("description").asString()).isEqualTo("Pix recebido");

        assertThat(mvc.delete().uri("/api/transfers/rejections").headers(auth(token)).exchange()).hasStatus(204);
        assertThat(body(mvc.get().uri("/api/transfers/suggestions").headers(auth(token)).exchange()).get("items")).hasSize(4);
    }

    private static HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private JsonNode body(MvcTestResult r) {
        return json.readTree(r.getResponse().getContentAsByteArray());
    }
}
