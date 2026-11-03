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

/**
 * Exportar os dados da conta (zip com JSON sem ids + fotos) e importar somando: nada duplica ao importar de novo,
 * e noutra conta tudo é recriado casando contas e categorias pelo nome.
 */
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
    void exportHasNoIdsAndImportMergesWithoutDuplicating() throws Exception {
        Session s = register("bk");
        JsonNode cats = body(mvc.get().uri("/api/categories").headers(auth(s.token)).exchange());
        long categoryId = cats.get(0).get("id").asLong();
        String categoryName = cats.get(0).get("name").asString();
        long sub = body(mvc.post().uri("/api/categories").headers(auth(s.token)).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Padaria", "type", cats.get(0).get("type").asString(), "color", "#112233",
                "icon", "croissant", "parentId", categoryId))).exchange()).get("id").asLong();
        mvc.post().uri("/api/category-rules").headers(auth(s.token)).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("keyword", "ifood", "categoryId", sub))).exchange();
        long savings = body(mvc.post().uri("/api/accounts").headers(auth(s.token)).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Poupança", "type", "SAVINGS", "initialBalance", 100, "color", "#123456"))).exchange()).get("id").asLong();
        String receipt = "u" + s.userId + "-" + java.util.UUID.randomUUID() + ".jpg";
        storage.storeRaw(s.userId, receipt, new byte[] { (byte) 0xFF, (byte) 0xD8, 1, 2, 3 });
        mvc.post().uri("/api/transactions").headers(auth(s.token)).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("description", "Mercado", "amount", "31.97", "type", "EXPENSE", "dueDate", "2026-10-25",
                "paid", true, "accountId", s.accountId, "categoryId", sub, "merchant", "ALMEIDA", "receiptFile", receipt,
                "items", List.of(Map.of("description", "LEITE", "quantity", 2, "unitPrice", "4.79", "total", "9.58"),
                    Map.of("description", "PAO", "total", "22.39"))))).exchange();
        mvc.post().uri("/api/transfers").headers(auth(s.token)).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("fromAccountId", s.accountId, "toAccountId", savings, "amount", "50.00",
                "date", "2026-10-26", "paid", true))).exchange();

        MvcTestResult exported = mvc.get().uri("/api/backup/export").headers(auth(s.token)).exchange();
        assertThat(exported).hasStatusOk();
        byte[] zip = exported.getResponse().getContentAsByteArray();
        assertThat(entries(zip)).startsWith("contaslt.json").contains("receipts/" + receipt);
        JsonNode data = jsonEntry(zip);
        assertThat(data.get("format").asString()).isEqualTo("contaslt-data");
        JsonNode mercado = find(data.get("transactions"), "Mercado", null);
        assertThat(mercado.has("id")).isFalse();                                  // nada de id de banco
        assertThat(mercado.get("account").asString()).isEqualTo("Conta principal");
        assertThat(mercado.get("category").asString()).isEqualTo(categoryName + " › Padaria");
        assertThat(mercado.get("items")).hasSize(2);
        JsonNode out = find(data.get("transactions"), "Transferência", "EXPENSE");
        assertThat(out.get("transferTo").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(data.get("categoryRules").get(0).get("category").asString()).isEqualTo(categoryName + " › Padaria");

        // Importar na mesma conta: tudo já existe, nada entra.
        JsonNode again = importZip(s, zip);
        assertThat(again.get("transactions").asInt()).isZero();
        assertThat(again.get("skippedTransactions").asInt()).isEqualTo(3);
        assertThat(again.get("accounts").asInt()).isZero();
        assertThat(again.get("categories").asInt()).isZero();
        assertThat(again.get("categoryRules").asInt()).isZero();
        assertThat(list(s).get("items")).hasSize(3);
        assertThat(body(mvc.get().uri("/api/accounts").headers(auth(s.token)).exchange())).hasSize(2);

        // Apagou um lançamento por engano? Importar de novo repõe só ele.
        long gone = find(list(s).get("items"), "Mercado", null).get("id").asLong();
        mvc.delete().uri("/api/transactions/" + gone).headers(auth(s.token)).exchange();
        JsonNode repaired = importZip(s, zip);
        assertThat(repaired.get("transactions").asInt()).isEqualTo(1);
        assertThat(repaired.get("skippedTransactions").asInt()).isEqualTo(2);
        JsonNode back = find(list(s).get("items"), "Mercado", null);
        assertThat(back.get("items")).hasSize(2);
        assertThat(back.get("receiptFile").asString()).isEqualTo(receipt);
        assertThat(mvc.get().uri("/api/receipts/" + receipt).headers(auth(s.token)).exchange()).hasStatusOk();

        // Noutra conta: contas e categorias casadas/criadas pelo nome, transferência pareada, foto com o prefixo novo.
        Session other = register("bk2");
        JsonNode moved = importZip(other, zip);
        assertThat(moved.get("transactions").asInt()).isEqualTo(3);
        assertThat(moved.get("accounts").asInt()).isEqualTo(1);        // "Conta principal" já existia; "Poupança" entrou
        assertThat(moved.get("categories").asInt()).isEqualTo(1);      // só "Padaria": as padrão já existiam pelo nome
        assertThat(moved.get("transfers").asInt()).isEqualTo(1);
        assertThat(moved.get("receipts").asInt()).isEqualTo(1);
        JsonNode otherPage = list(other);
        JsonNode movedMercado = find(otherPage.get("items"), "Mercado", null);
        assertThat(movedMercado.get("receiptFile").asString()).startsWith("u" + other.userId + "-");
        assertThat(movedMercado.get("categoryName").asString()).isEqualTo("Padaria");
        assertThat(mvc.get().uri("/api/receipts/" + movedMercado.get("receiptFile").asString()).headers(auth(other.token)).exchange()).hasStatusOk();
        assertThat(find(otherPage.get("items"), "Transferência", "EXPENSE").get("transferPeerAccountName").asString()).isEqualTo("Poupança");
        assertThat(importZip(other, zip).get("transactions").asInt()).isZero();   // de novo: nada duplica
        assertThat(list(s).get("items")).hasSize(3);                              // e a conta de origem não mudou
    }

    @Test
    void rejectsFilesThatAreNotExports() {
        Session s = register("bk3");
        byte[] notZip = "{\"format\":\"contaslt-data\"}".getBytes();
        assertThat(mvc.post().uri("/api/backup/import").headers(auth(s.token)).multipart()
            .file(new MockMultipartFile("file", "x.zip", "application/zip", notZip)).exchange()).hasStatus(422);
    }

    private JsonNode importZip(Session s, byte[] zip) {
        MvcTestResult r = mvc.post().uri("/api/backup/import").headers(auth(s.token)).multipart()
            .file(new MockMultipartFile("file", "dados.zip", "application/zip", zip)).exchange();
        assertThat(r).hasStatusOk();
        return body(r);
    }

    private JsonNode list(Session s) {
        return body(mvc.get().uri("/api/transactions?month=2026-10&transfers=").headers(auth(s.token)).exchange());
    }

    private static JsonNode find(JsonNode items, String descriptionPrefix, String type) {
        for (JsonNode t : items) {
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
