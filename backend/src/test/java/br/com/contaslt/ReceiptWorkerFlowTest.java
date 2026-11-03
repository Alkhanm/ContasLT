package br.com.contaslt;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fila do worker: a foto espera no servidor, o worker (autenticado pelo token) reserva, baixa a imagem e devolve o
 * JSON; o formulário aberto recebe o resultado pela consulta da tarefa, e um lançamento salvo enquanto a leitura
 * esperava recebe os itens, com aviso quando total ou data não batem.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "app.receipts.provider=worker", "app.worker.token=wtok-test" })
class ReceiptWorkerFlowTest {

    static final String MODEL_JSON = """
        {"merchant":"SUPERMERCADO ALMEIDA","issueDate":"2026-10-25","dueDate":"","paid":true,"total":31.97,
         "paymentMethod":"Pix","accessKey":"",
         "items":[{"description":"BATATA LAYS","quantity":2,"unitPrice":10.99,"total":21.98},
                  {"description":"ENERGETICO","quantity":1,"unitPrice":9.99,"total":9.99}]}
        """;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ObjectMapper json;

    private String token;
    private long accountId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "worker-" + System.nanoTime() + "@contaslt.app";
        MvcTestResult reg = mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "W", "email", email, "password", "segredo123"))).exchange();
        token = body(reg).get("token").asString();
        accountId = body(mvc.get().uri("/api/accounts").headers(auth()).exchange()).get(0).get("id").asLong();
    }

    @Test
    void workerRoutesRequireTheToken() {
        assertThat(mvc.post().uri("/api/worker/jobs/claim").exchange()).hasStatus(401);
        assertThat(mvc.post().uri("/api/worker/jobs/claim").header("X-Worker-Token", "errado").exchange()).hasStatus(401);
        assertThat(mvc.post().uri("/api/worker/ping").headers(worker()).exchange()).hasStatus(204);
    }

    @Test
    void openFormGetsTheResultThroughTheJob() throws Exception {
        JsonNode scan = body(scan());
        assertThat(scan.get("status").asString()).isEqualTo("PENDING");
        long jobId = scan.get("jobId").asLong();
        String file = scan.get("receiptFile").asString();

        JsonNode claimed = claimMine(jobId);
        assertThat(claimed.get("receiptFile").asString()).isEqualTo(file);
        assertThat(mvc.post().uri("/api/worker/jobs/claim").headers(worker()).exchange()).hasStatus(204); // fila vazia
        assertThat(mvc.get().uri("/api/worker/jobs/" + jobId + "/image").headers(worker()).exchange())
            .hasStatusOk().hasContentType(MediaType.IMAGE_JPEG);

        JsonNode waiting = body(mvc.get().uri("/api/receipts/jobs/" + jobId).headers(auth()).exchange());
        assertThat(waiting.get("status").asString()).isEqualTo("PENDING");
        assertThat(waiting.get("workerOnline").asBoolean()).isTrue();

        assertThat(mvc.post().uri("/api/worker/jobs/" + jobId + "/result").headers(worker()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("content", MODEL_JSON))).exchange()).hasStatus(204);

        JsonNode done = body(mvc.get().uri("/api/receipts/jobs/" + jobId).headers(auth()).exchange());
        assertThat(done.get("status").asString()).isEqualTo("DONE");
        assertThat(done.get("merchant").asString()).isEqualTo("SUPERMERCADO ALMEIDA");
        assertThat(done.get("total").decimalValue()).isEqualByComparingTo("31.97");
        assertThat(done.get("date").asString()).isEqualTo("2026-10-25");
        assertThat(done.get("items")).hasSize(2);
        assertThat(mvc.get().uri("/api/receipts/jobs/999999").headers(auth()).exchange()).hasStatus(404);
    }

    @Test
    void transactionSavedWhileWaitingReceivesItemsAndDivergenceNotice() throws Exception {
        JsonNode scan = body(scan());
        long jobId = scan.get("jobId").asLong();
        String file = scan.get("receiptFile").asString();

        JsonNode tx = body(mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("description", "Mercado", "amount", "50.00", "type", "EXPENSE",
                "dueDate", "2026-10-20", "paid", true, "accountId", accountId, "receiptFile", file))).exchange());
        long txId = tx.get("id").asLong();
        assertThat(tx.get("receiptScanStatus").asString()).isEqualTo("PENDING");

        claimMine(jobId);
        assertThat(mvc.post().uri("/api/worker/jobs/" + jobId + "/result").headers(worker()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("content", MODEL_JSON))).exchange()).hasStatus(204);

        JsonNode read = body(mvc.get().uri("/api/transactions/" + txId).headers(auth()).exchange());
        assertThat(read.get("receiptScanStatus").asString()).isEqualTo("DIVERGENT");
        assertThat(read.get("receiptScanTotal").decimalValue()).isEqualByComparingTo("31.97");
        assertThat(read.get("receiptScanDate").asString()).isEqualTo("2026-10-25");
        assertThat(read.get("amount").decimalValue()).isEqualByComparingTo("50.00");   // o lançado fica até o usuário decidir
        assertThat(read.get("items")).hasSize(2);
        assertThat(read.get("merchant").asString()).isEqualTo("SUPERMERCADO ALMEIDA");

        JsonNode accepted = body(mvc.post().uri("/api/transactions/" + txId + "/receipt-scan").headers(auth())
            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"ACCEPT\"}").exchange());
        assertThat(accepted.get("amount").decimalValue()).isEqualByComparingTo("31.97");
        assertThat(accepted.get("dueDate").asString()).isEqualTo("2026-10-25");
        assertThat(accepted.get("receiptScanStatus").isNull()).isTrue();
        assertThat(accepted.get("items")).hasSize(2);
    }

    @Test
    void matchingValuesGiveNoNoticeAndDeliveredResultIsNotReappliedOnSave() throws Exception {
        JsonNode scan = body(scan());
        long jobId = scan.get("jobId").asLong();
        String file = scan.get("receiptFile").asString();
        claimMine(jobId);
        mvc.post().uri("/api/worker/jobs/" + jobId + "/result").headers(worker()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("content", MODEL_JSON))).exchange();
        // O formulário ainda aberto recebe o resultado e o usuário ajusta o valor antes de salvar.
        assertThat(body(mvc.get().uri("/api/receipts/jobs/" + jobId).headers(auth()).exchange()).get("status").asString()).isEqualTo("DONE");

        JsonNode tx = body(mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("description", "Mercado", "amount", "30.00", "type", "EXPENSE",
                "dueDate", "2026-10-25", "paid", true, "accountId", accountId, "receiptFile", file))).exchange());
        assertThat(tx.get("receiptScanStatus").isNull()).isTrue();   // entregue ao formulário: não reaplica nem avisa
        assertThat(tx.get("items")).isEmpty();
    }

    @Test
    void failuresGoBackToTheQueueThenGiveUpAndCanBeRetried() throws Exception {
        JsonNode scan = body(scan());
        long jobId = scan.get("jobId").asLong();
        String file = scan.get("receiptFile").asString();
        long txId = body(mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("description", "Mercado", "amount", "31.97", "type", "EXPENSE",
                "dueDate", "2026-10-25", "paid", true, "accountId", accountId, "receiptFile", file))).exchange()).get("id").asLong();

        // Ollama caiu: volta para a fila sem gastar tentativa.
        claimMine(jobId);
        fail(jobId, true);
        assertThat(status(txId)).isEqualTo("PENDING");
        for (int i = 0; i < 3; i++) {
            assertThat(body(mvc.post().uri("/api/worker/jobs/claim").headers(worker()).exchange()).get("id").asLong()).isEqualTo(jobId);
            fail(jobId, false);
        }
        assertThat(status(txId)).isEqualTo("FAILED");
        assertThat(mvc.post().uri("/api/worker/jobs/claim").headers(worker()).exchange()).hasStatus(204);

        JsonNode retried = body(mvc.post().uri("/api/transactions/" + txId + "/receipt-scan").headers(auth())
            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"RETRY\"}").exchange());
        assertThat(retried.get("receiptScanStatus").asString()).isEqualTo("PENDING");
        JsonNode again = body(mvc.post().uri("/api/worker/jobs/claim").headers(worker()).exchange());
        assertThat(again.get("receiptFile").asString()).isEqualTo(file);
        mvc.post().uri("/api/worker/jobs/" + again.get("id").asLong() + "/result").headers(worker()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("content", MODEL_JSON))).exchange();
        JsonNode read = body(mvc.get().uri("/api/transactions/" + txId).headers(auth()).exchange());
        assertThat(read.get("receiptScanStatus").isNull()).isTrue();   // mesmo valor e data: sem aviso
        assertThat(read.get("items")).hasSize(2);
    }

    /**
     * Os testes compartilham o contexto (e a fila): reserva até chegar na tarefa deste teste, concluindo as que
     * outros testes deixaram para trás.
     */
    private JsonNode claimMine(long jobId) throws Exception {
        for (int i = 0; i < 20; i++) {
            MvcTestResult r = mvc.post().uri("/api/worker/jobs/claim").headers(worker()).exchange();
            assertThat(r).hasStatusOk();
            JsonNode claimed = body(r);
            if (claimed.get("id").asLong() == jobId) {
                return claimed;
            }
            mvc.post().uri("/api/worker/jobs/" + claimed.get("id").asLong() + "/result").headers(worker())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("content", MODEL_JSON))).exchange();
        }
        throw new AssertionError("A tarefa " + jobId + " não apareceu na fila");
    }

    private void fail(long jobId, boolean retryable) throws Exception {
        assertThat(mvc.post().uri("/api/worker/jobs/" + jobId + "/fail").headers(worker()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("error", "modelo falhou", "retryable", retryable))).exchange()).hasStatus(204);
    }

    private String status(long txId) {
        JsonNode s = body(mvc.get().uri("/api/transactions/" + txId).headers(auth()).exchange()).get("receiptScanStatus");
        return s == null || s.isNull() ? null : s.asString();
    }

    private MvcTestResult scan() throws Exception {
        BufferedImage img = new BufferedImage(60, 80, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", out);
        MockMultipartFile photo = new MockMultipartFile("file", "cupom.jpg", "image/jpeg", out.toByteArray());
        MvcTestResult r = mvc.post().uri("/api/receipts/scan").headers(auth()).multipart().file(photo).exchange();
        assertThat(r).hasStatusOk();
        return r;
    }

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private static HttpHeaders worker() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Worker-Token", "wtok-test");
        return h;
    }

    private JsonNode body(MvcTestResult r) {
        return json.readTree(r.getResponse().getContentAsByteArray());
    }
}
