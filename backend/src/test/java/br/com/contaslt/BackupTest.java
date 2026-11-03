package br.com.contaslt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import br.com.contaslt.receipt.ReceiptStorage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Backup completo: exportar um zip (JSON + fotos) e restaurar, no mesmo usuário e em outro (ids e fotos remapeados). */
@SpringBootTest
@AutoConfigureMockMvc
class BackupTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    ReceiptStorage storage;

    record Session(String token, long userId, long accountId) {
    }

    private Session register(String prefix) {
        String email = prefix + "-" + System.nanoTime() + "@contaslt.app";
        JsonNode reg = body(mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "B", "email", email, "password", "segredo123"))).exchange());
        String token = reg.get("token").asString();
        long accountId = body(mvc.get().uri("/api/accounts").headers(auth(token)).exchange()).get(0).get("id").asLong();
        return new Session(token, reg.get("user").get("id").asLong(), accountId);
    }

    @Test
    void exportThenRestoreBringsEverythingBack() throws Exception {
        Session s = register("bk");
        long categoryId = body(mvc.get().uri("/api/categories").headers(auth(s.token)).exchange()).get(0).get("id").asLong();
        mvc.post().uri("/api/category-rules").headers(auth(s.token)).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("keyword", "ifood", "categoryId", categoryId))).exchange();
        long savings = body(mvc.post().uri("/api/accounts").headers(auth(s.token)).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Poupança", "type", "SAVINGS", "initialBalance", 100, "color", "#123456"))).exchange()).get("id").asLong();
        String receipt = "u" + s.userId + "-" + java.util.UUID.randomUUID() + ".jpg";
        storage.storeRaw(s.userId, receipt, new byte[] { (byte) 0xFF, (byte) 0xD8, 1, 2, 3 });
        long txId = body(mvc.post().uri("/api/transactions").headers(auth(s.token)).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("description", "Mercado", "amount", "31.97", "type", "EXPENSE", "dueDate", "2026-10-25",
                "paid", true, "accountId", s.accountId, "categoryId", categoryId, "merchant", "ALMEIDA", "receiptFile", receipt,
                "items", List.of(Map.of("description", "LEITE", "quantity", 2, "unitPrice", "4.79", "total", "9.58"),
                    Map.of("description", "PAO", "total", "22.39"))))).exchange()).get("id").asLong();
        mvc.post().uri("/api/transfers").headers(auth(s.token)).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("fromAccountId", s.accountId, "toAccountId", savings, "amount", "50.00",
                "date", "2026-10-26", "paid", true))).exchange();

        MvcTestResult exported = mvc.get().uri("/api/backup/export").headers(auth(s.token)).exchange();
        assertThat(exported).hasStatusOk();
        assertThat(exported.getResponse().getHeader("Content-Disposition")).contains("contaslt-backup-");
        byte[] zip = exported.getResponse().getContentAsByteArray();
        List<String> entries = entries(zip);
        assertThat(entries.get(0)).isEqualTo("contaslt.json");
        assertThat(entries).contains("receipts/" + receipt);
        JsonNode snapshot = jsonEntry(zip);
        assertThat(snapshot.get("format").asString()).isEqualTo("contaslt-backup");
        assertThat(snapshot.get("accounts")).hasSize(2);
        assertThat(snapshot.get("transactions")).hasSize(3);
        assertThat(snapshot.get("transfers")).hasSize(1);
        assertThat(snapshot.get("categoryRules")).hasSize(1);

        // Estraga tudo: apaga o lançamento com itens e o vínculo da transferência.
        assertThat(mvc.delete().uri("/api/transactions/" + txId).headers(auth(s.token)).exchange()).hasStatus(204);
        assertThat(list(s, "2026-10").get("items")).hasSize(2);

        JsonNode restored = body(mvc.post().uri("/api/backup/import").headers(auth(s.token)).multipart()
            .file(new MockMultipartFile("file", "backup.zip", "application/zip", zip)).exchange());
        assertThat(restored.get("transactions").asInt()).isEqualTo(3);
        assertThat(restored.get("accounts").asInt()).isEqualTo(2);
        assertThat(restored.get("transfers").asInt()).isEqualTo(1);
        assertThat(restored.get("receipts").asInt()).isEqualTo(1);

        JsonNode page = list(s, "2026-10");
        assertThat(page.get("items")).hasSize(3);
        JsonNode mercado = find(page, "Mercado");
        assertThat(mercado.get("items")).hasSize(2);
        assertThat(mercado.get("merchant").asString()).isEqualTo("ALMEIDA");
        assertThat(mercado.get("categoryId").asLong()).isPositive();
        assertThat(mercado.get("receiptFile").asString()).isEqualTo(receipt);
        assertThat(mvc.get().uri("/api/receipts/" + receipt).headers(auth(s.token)).exchange()).hasStatusOk();
        JsonNode leg = find(page, "Transferência", "EXPENSE");
        assertThat(leg.get("transferId").isNull()).isFalse();
        assertThat(leg.get("transferPeerTransactionId").isNull()).isFalse();
        assertThat(body(mvc.get().uri("/api/category-rules").headers(auth(s.token)).exchange())).hasSize(1);
        assertThat(body(mvc.get().uri("/api/accounts").headers(auth(s.token)).exchange())).hasSize(2);

        // O mesmo zip restaurado em OUTRO usuário: ids remapeados e fotos com o novo prefixo.
        Session other = register("bk2");
        JsonNode moved = body(mvc.post().uri("/api/backup/import").headers(auth(other.token)).multipart()
            .file(new MockMultipartFile("file", "backup.zip", "application/zip", zip)).exchange());
        assertThat(moved.get("transactions").asInt()).isEqualTo(3);
        JsonNode otherPage = list(other, "2026-10");
        String movedReceipt = find(otherPage, "Mercado").get("receiptFile").asString();
        assertThat(movedReceipt).startsWith("u" + other.userId + "-").endsWith(receipt.substring(receipt.indexOf('-')));
        assertThat(mvc.get().uri("/api/receipts/" + movedReceipt).headers(auth(other.token)).exchange()).hasStatusOk();
        assertThat(find(otherPage, "Transferência", "EXPENSE").get("transferPeerAccountName").asString()).isEqualTo("Poupança");
        // E o primeiro usuário continua com os dele.
        assertThat(list(s, "2026-10").get("items")).hasSize(3);
    }

    @Test
    void rejectsFilesThatAreNotBackups() {
        Session s = register("bk3");
        byte[] notZip = "{\"format\":\"contaslt-backup\"}".getBytes();
        assertThat(mvc.post().uri("/api/backup/import").headers(auth(s.token)).multipart()
            .file(new MockMultipartFile("file", "x.zip", "application/zip", notZip)).exchange()).hasStatus(422);
    }

    private JsonNode list(Session s, String month) {
        return body(mvc.get().uri("/api/transactions?month=" + month + "&transfers=").headers(auth(s.token)).exchange());
    }

    private static JsonNode find(JsonNode page, String descriptionPrefix) {
        return find(page, descriptionPrefix, null);
    }

    private static JsonNode find(JsonNode page, String descriptionPrefix, String type) {
        for (JsonNode t : page.get("items")) {
            if (t.get("description").asString().startsWith(descriptionPrefix) && (type == null || type.equals(t.get("type").asString()))) {
                return t;
            }
        }
        throw new AssertionError("não achei " + descriptionPrefix);
    }

    private static List<String> entries(byte[] zip) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                names.add(e.getName());
            }
        }
        return names;
    }

    private JsonNode jsonEntry(byte[] zip) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            in.getNextEntry();
            return json.readTree(in.readAllBytes());
        }
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
