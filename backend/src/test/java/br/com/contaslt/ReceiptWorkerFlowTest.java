package br.com.contaslt;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;

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
 * JSON; o formulário aberto recebe a leitura pela consulta da tarefa, e a leitura de uma foto já salva num lançamento
 * do app fica esperando o app buscá-la (e aplicá-la nos dados dele) uma vez.
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

    @BeforeEach
    void setUp() throws Exception {
        String email = "worker-" + System.nanoTime() + "@contaslt.app";
        MvcTestResult reg = mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "W", "email", email, "password", "segredo123"))).exchange();
        token = body(reg).get("token").asString();
    }

    @Test
    void workerRoutesRequireTheToken() {
        assertThat(mvc.post().uri("/api/worker/jobs/claim").exchange()).hasStatus(401);
        assertThat(mvc.post().uri("/api/worker/jobs/claim").header("X-Worker-Token", "errado").exchange()).hasStatus(401);
        assertThat(mvc.post().uri("/api/worker/ping").headers(worker()).exchange()).hasStatus(204);
    }

    @Test
    void openFormGetsTheRawReadThroughTheJob() throws Exception {
        JsonNode scan = body(scan());
        assertThat(scan.get("status").asString()).isEqualTo("PENDING");
        assertThat(scan.get("scan").isNull()).isTrue();
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

        complete(jobId);

        JsonNode done = body(mvc.get().uri("/api/receipts/jobs/" + jobId).headers(auth()).exchange());
        assertThat(done.get("status").asString()).isEqualTo("DONE");
        JsonNode read = done.get("scan");
        assertThat(read.get("merchant").asString()).isEqualTo("SUPERMERCADO ALMEIDA");
        assertThat(read.get("total").decimalValue()).isEqualByComparingTo("31.97");
        assertThat(read.get("issueDate").asString()).isEqualTo("2026-10-25");
        assertThat(read.get("items")).hasSize(2);
        assertThat(mvc.get().uri("/api/receipts/jobs/999999").headers(auth()).exchange()).hasStatus(404);

        // Já visto pelo formulário: ao salvar o lançamento, o vínculo não devolve a leitura de novo.
        JsonNode attached = attach(file, UUID.randomUUID());
        assertThat(attached.get("status").asString()).isEqualTo("DONE");
        assertThat(attached.get("scan").isNull()).isTrue();
    }

    @Test
    void transactionSavedWhileWaitingGetsTheResultOnce() throws Exception {
        JsonNode scan = body(scan());
        long jobId = scan.get("jobId").asLong();
        String file = scan.get("receiptFile").asString();
        UUID tx = UUID.randomUUID();

        assertThat(attach(file, tx).get("status").asString()).isEqualTo("PENDING");
        assertThat(results()).isEmpty();

        claimMine(jobId);
        complete(jobId);

        JsonNode pending = results();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).get("transactionId").asString()).isEqualTo(tx.toString());
        assertThat(pending.get(0).get("scan").get("items")).hasSize(2);

        // O app aplicou nos dados dele e confirma; ou liga de novo e recebe a leitura uma vez só.
        JsonNode again = attach(file, tx);
        assertThat(again.get("scan").get("merchant").asString()).isEqualTo("SUPERMERCADO ALMEIDA");
        assertThat(results()).isEmpty();
        assertThat(attach(file, tx).get("scan").isNull()).isTrue();
    }

    @Test
    void failuresGoBackToTheQueueThenGiveUpAndCanBeRetried() throws Exception {
        JsonNode scan = body(scan());
        long jobId = scan.get("jobId").asLong();
        String file = scan.get("receiptFile").asString();
        UUID tx = UUID.randomUUID();
        attach(file, tx);

        // Ollama caiu: volta para a fila sem gastar tentativa.
        claimMine(jobId);
        fail(jobId, true);
        assertThat(body(mvc.get().uri("/api/receipts/jobs/" + jobId).headers(auth()).exchange()).get("status").asString())
            .isEqualTo("PENDING");
        for (int i = 0; i < 3; i++) {
            assertThat(body(mvc.post().uri("/api/worker/jobs/claim").headers(worker()).exchange()).get("id").asLong()).isEqualTo(jobId);
            fail(jobId, false);
        }
        JsonNode failed = results();
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).get("status").asString()).isEqualTo("FAILED");
        assertThat(mvc.post().uri("/api/receipts/jobs/" + jobId + "/delivered").headers(auth()).exchange()).hasStatus(204);
        assertThat(results()).isEmpty();
        assertThat(mvc.post().uri("/api/worker/jobs/claim").headers(worker()).exchange()).hasStatus(204);

        JsonNode retried = body(mvc.post().uri("/api/receipts/retry").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("receiptFile", file, "transactionId", tx.toString()))).exchange());
        assertThat(retried.get("status").asString()).isEqualTo("PENDING");
        JsonNode next = body(mvc.post().uri("/api/worker/jobs/claim").headers(worker()).exchange());
        assertThat(next.get("receiptFile").asString()).isEqualTo(file);
        complete(next.get("id").asLong());
        assertThat(results().get(0).get("scan").get("items")).hasSize(2);
    }

    @Test
    void uploadStoresWithoutReading() throws Exception {
        MvcTestResult r = mvc.post().uri("/api/receipts").headers(auth()).multipart().file(photo()).exchange();
        assertThat(r).hasStatusOk();
        String file = body(r).get("receiptFile").asString();
        assertThat(mvc.get().uri("/api/receipts/" + file).headers(auth()).exchange()).hasStatusOk();
        assertThat(mvc.post().uri("/api/worker/jobs/claim").headers(worker()).exchange()).satisfiesAnyOf(
            res -> assertThat(res).hasStatus(204),
            res -> assertThat(body(res).get("receiptFile").asString()).isNotEqualTo(file));
    }

    private JsonNode attach(String file, UUID tx) throws Exception {
        MvcTestResult r = mvc.post().uri("/api/receipts/attach").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("receiptFile", file, "transactionId", tx.toString()))).exchange();
        assertThat(r).hasStatusOk();
        return body(r);
    }

    private JsonNode results() {
        return body(mvc.get().uri("/api/receipts/results").headers(auth()).exchange());
    }

    private void complete(long jobId) throws Exception {
        assertThat(mvc.post().uri("/api/worker/jobs/" + jobId + "/result").headers(worker()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("content", MODEL_JSON))).exchange()).hasStatus(204);
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

    private static MockMultipartFile photo() throws Exception {
        BufferedImage img = new BufferedImage(60, 80, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", out);
        return new MockMultipartFile("file", "cupom.jpg", "image/jpeg", out.toByteArray());
    }

    private MvcTestResult scan() throws Exception {
        MvcTestResult r = mvc.post().uri("/api/receipts/scan").headers(auth()).multipart().file(photo()).exchange();
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
