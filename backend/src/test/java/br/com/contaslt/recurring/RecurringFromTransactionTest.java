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

/** Transformar um lançamento existente (ex.: importado do extrato) em conta recorrente. */
@SpringBootTest
@AutoConfigureMockMvc
class RecurringFromTransactionTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ObjectMapper json;

    private String token;
    private long accountId;

    @BeforeEach
    void setUp() {
        String email = "rec" + System.nanoTime() + "@exemplo.com";
        token = body(mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Rec", "email", email, "password", "segredo1")))
            .exchange()).get("token").asString();
        accountId = body(mvc.get().uri("/api/accounts").headers(auth()).exchange()).get(0).get("id").asLong();
    }

    @Test
    void importedTransactionBecomesFirstOccurrenceWithoutDuplicate() {
        long txId = transaction("PAGTO ENEL DISTRIBUICAO", "2031-05-10");

        Map<String, Object> bill = new HashMap<>();
        bill.put("description", "Conta de luz Enel");
        bill.put("amount", 189.90);
        bill.put("type", "EXPENSE");
        bill.put("frequency", "MONTHLY");
        bill.put("startDate", "2031-05-10");
        bill.put("accountId", accountId);
        bill.put("fromTransactionId", txId);
        MvcTestResult created = mvc.post().uri("/api/recurring-bills").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(bill)).exchange();
        assertThat(created).hasStatus(201);
        long billId = body(created).get("id").asLong();

        // Maio: continua um lançamento só, agora vinculado à regra e com a descrição original preservada.
        JsonNode may = body(mvc.get().uri("/api/transactions?month=2031-05").headers(auth()).exchange()).get("items");
        assertThat(may).hasSize(1);
        assertThat(may.get(0).get("id").asLong()).isEqualTo(txId);
        assertThat(may.get(0).get("recurringBillId").asLong()).isEqualTo(billId);
        assertThat(may.get(0).get("description").asString()).isEqualTo("PAGTO ENEL DISTRIBUICAO");
        assertThat(may.get(0).get("paid").asBoolean()).isTrue();

        // Junho: a regra gera o próximo vencimento, pendente.
        JsonNode june = body(mvc.get().uri("/api/transactions?month=2031-06").headers(auth()).exchange()).get("items");
        assertThat(june).hasSize(1);
        assertThat(june.get(0).get("recurringBillId").asLong()).isEqualTo(billId);
        assertThat(june.get(0).get("dueDate").asString()).isEqualTo("2031-06-10");
        assertThat(june.get(0).get("paid").asBoolean()).isFalse();

        // O mesmo lançamento não pode virar duas regras.
        bill.put("description", "Outra");
        assertThat(mvc.post().uri("/api/recurring-bills").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(bill)).exchange()).hasStatus(422);
    }

    private long transaction(String description, String dueDate) {
        Map<String, Object> m = new HashMap<>();
        m.put("description", description);
        m.put("amount", 189.90);
        m.put("type", "EXPENSE");
        m.put("dueDate", dueDate);
        m.put("paid", true);
        m.put("accountId", accountId);
        MvcTestResult res = mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(m)).exchange();
        assertThat(res).hasStatus(201);
        return body(res).get("id").asLong();
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
