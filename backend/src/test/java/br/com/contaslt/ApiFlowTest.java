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
    void deletingImportRemovesItsTransactions() {
        JsonNode history = body(mvc.get().uri("/api/imports").headers(auth()).exchange());
        assertThat(history.size()).isGreaterThanOrEqualTo(1);
        long batchId = history.get(history.size() - 1).get("id").asLong();
        int before = body(mvc.get().uri("/api/transactions?month=2026-10").headers(auth()).exchange()).get("items").size();

        MvcTestResult res = mvc.delete().uri("/api/imports/" + batchId).headers(auth()).exchange();
        assertThat(res).hasStatusOk();
        assertThat(body(res).get("removed").asInt()).isEqualTo(3);

        int after = body(mvc.get().uri("/api/transactions?month=2026-10").headers(auth()).exchange()).get("items").size();
        assertThat(after).isEqualTo(before - 3);
        assertThat(mvc.delete().uri("/api/imports/" + batchId).headers(auth()).exchange()).hasStatus(404);
    }

    @Test
    @Order(8)
    void transfersBetweenAccountsAreNeutral() {
        // Segunda conta para receber a transferência.
        MvcTestResult acc = mvc.post().uri("/api/accounts").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Conta PJ", "type", "CHECKING", "initialBalance", 0)))
            .exchange();
        assertThat(acc).hasStatus(201);
        long pjId = body(acc).get("id").asLong();

        String month = "2027-03";
        // Receita real de 1000 no mês, para comparar os totais.
        assertThat(mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("description", "Cliente", "amount", 1000, "type", "INCOME",
                "dueDate", month + "-01", "paid", true, "accountId", pjId, "categoryId", incomeCategoryId)))
            .exchange()).hasStatus(201);

        // 1) Transferência criada diretamente: duas pernas vinculadas.
        MvcTestResult created = mvc.post().uri("/api/transfers").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("fromAccountId", pjId, "toAccountId", accountId,
                "amount", 600, "date", month + "-05", "description", "Pró-labore")))
            .exchange();
        assertThat(created).hasStatus(201);
        JsonNode tr = body(created);
        assertThat(tr.get("from").get("type").asString()).isEqualTo("EXPENSE");
        assertThat(tr.get("to").get("type").asString()).isEqualTo("INCOME");
        assertThat(tr.get("to").get("transferPeerAccountId").asLong()).isEqualTo(pjId);

        // Além dos lançamentos acima, o aluguel recorrente do teste 3 (1600) é materializado neste mês.
        JsonNode page = body(mvc.get().uri("/api/transactions?month=" + month).headers(auth()).exchange());
        assertThat(page.get("items")).hasSize(4);
        assertThat(page.get("summary").get("income").decimalValue()).isEqualByComparingTo("1000");
        assertThat(page.get("summary").get("expense").decimalValue()).isEqualByComparingTo("1600");
        assertThat(page.get("summary").get("transferOut").decimalValue()).isEqualByComparingTo("600");
        assertThat(page.get("summary").get("transferIn").decimalValue()).isEqualByComparingTo("600");
        // O saldo de cada conta continua considerando as pernas.
        JsonNode accounts = body(mvc.get().uri("/api/accounts").headers(auth()).exchange());
        for (JsonNode a : accounts) {
            if (a.get("id").asLong() == pjId) {
                assertThat(a.get("balance").decimalValue()).isEqualByComparingTo("400");
            }
        }
        // Filtro "só transferências".
        assertThat(body(mvc.get().uri("/api/transactions?month=" + month + "&transfers=true").headers(auth()).exchange())
            .get("items")).hasSize(2);

        // 2) Cruzamento automático: saída numa conta e entrada de mesmo valor na outra, 1 dia depois.
        long outId = body(mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("description", "Transferência enviada pelo Pix", "amount", 250,
                "type", "EXPENSE", "dueDate", month + "-10", "paid", true, "accountId", pjId)))
            .exchange()).get("id").asLong();
        long inId = body(mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("description", "Transferência recebida", "amount", 250,
                "type", "INCOME", "dueDate", month + "-11", "paid", true, "accountId", accountId)))
            .exchange()).get("id").asLong();
        // Ruído: receita de mesmo valor na MESMA conta da saída não pode ser sugerida.
        mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("description", "Reembolso", "amount", 250,
                "type", "INCOME", "dueDate", month + "-10", "paid", true, "accountId", pjId)))
            .exchange();

        JsonNode suggestions = body(mvc.get().uri("/api/transfers/suggestions").headers(auth()).exchange()).get("items");
        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).get("from").get("id").asLong()).isEqualTo(outId);
        assertThat(suggestions.get(0).get("to").get("id").asLong()).isEqualTo(inId);
        assertThat(suggestions.get(0).get("daysApart").asInt()).isEqualTo(1);

        MvcTestResult linked = mvc.post().uri("/api/transfers/link").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("transactionId", inId, "otherTransactionId", outId)))
            .exchange();
        assertThat(linked).hasStatus(201);
        long transferId = body(linked).get("id").asLong();
        assertThat(body(linked).get("from").get("id").asLong()).isEqualTo(outId);

        JsonNode dash = body(mvc.get().uri("/api/dashboard?month=" + month).headers(auth()).exchange());
        assertThat(dash.get("income").decimalValue()).isEqualByComparingTo("1250");
        assertThat(dash.get("expense").decimalValue()).isEqualByComparingTo("1600");
        assertThat(dash.get("transfers").decimalValue()).isEqualByComparingTo("850");
        assertThat(body(mvc.get().uri("/api/transfers/suggestions").headers(auth()).exchange()).get("items")).isEmpty();

        // Vincular de novo é recusado; valores diferentes também.
        assertThat(mvc.post().uri("/api/transfers/link").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("transactionId", inId, "otherTransactionId", outId)))
            .exchange()).hasStatus(422);

        // 3) Desvincular devolve os dois lançamentos ao resultado.
        assertThat(mvc.delete().uri("/api/transfers/" + transferId).headers(auth()).exchange()).hasStatus(204);
        dash = body(mvc.get().uri("/api/dashboard?month=" + month).headers(auth()).exchange());
        assertThat(dash.get("income").decimalValue()).isEqualByComparingTo("1500");
        assertThat(dash.get("expense").decimalValue()).isEqualByComparingTo("1850");

        // 4) Excluir uma perna desfaz o vínculo e deixa a outra solta.
        assertThat(mvc.delete().uri("/api/transactions/" + tr.get("from").get("id").asLong()).headers(auth()).exchange())
            .hasStatus(204);
        JsonNode other = body(mvc.get().uri("/api/transactions/" + tr.get("to").get("id").asLong()).headers(auth()).exchange());
        assertThat(other.get("transferId").isNull()).isTrue();
    }

    @Test
    @Order(9)
    void receiptTransactionWithItemsIsLinkedByOfxImport() throws Exception {
        // Lançamento feito a partir de um comprovante (itens + estabelecimento), um dia antes da linha do extrato.
        MvcTestResult created = mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of(
                "description", "Padaria Pão Quente", "merchant", "Padaria Pão Quente", "amount", 52.30,
                "type", "EXPENSE", "dueDate", "2026-10-06", "paid", true, "accountId", accountId,
                "categoryId", expenseCategoryId, "receiptAccessKey", "1".repeat(44),
                "items", List.of(
                    Map.of("description", "Pão francês", "quantity", 10, "unitPrice", 0.80, "total", 8.00),
                    Map.of("description", "Bolo de cenoura", "quantity", 1, "unitPrice", 44.30, "total", 44.30)))))
            .exchange();
        assertThat(created).hasStatus(201);
        JsonNode tx = body(created);
        long txId = tx.get("id").asLong();
        assertThat(tx.get("items")).hasSize(2);
        assertThat(tx.get("items").get(1).get("description").asString()).isEqualTo("Bolo de cenoura");

        // Comprovante de outro usuário não pode ser anexado.
        assertThat(mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("description", "x", "amount", 1, "type", "EXPENSE",
                "dueDate", "2026-10-06", "accountId", accountId, "receiptFile", "u999-" + java.util.UUID.randomUUID() + ".jpg")))
            .exchange()).hasStatus(422);

        // A prévia do OFX reconhece a linha de 52,30 (07/10) como a mesma compra.
        byte[] ofx = getClass().getResourceAsStream("/samples/extrato.ofx").readAllBytes();
        MockMultipartFile file = new MockMultipartFile("file", "extrato.ofx", "application/x-ofx", ofx);
        JsonNode pv = body(mvc.post().uri("/api/imports/preview").headers(auth()).multipart()
            .file(file).param("accountId", String.valueOf(accountId)).exchange());
        JsonNode matched = null;
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (JsonNode r : pv.get("rows")) {
            Map<String, Object> row = new java.util.HashMap<>(Map.of(
                "date", r.get("date").asString(), "description", r.get("description").asString(),
                "amount", r.get("amount").decimalValue(), "type", r.get("type").asString(),
                "externalId", r.get("externalId").asString()));
            if (!r.get("matchTransactionId").isNull()) {
                matched = r;
                row.put("linkTransactionId", r.get("matchTransactionId").asLong());
            } else {
                row.put("categoryId", r.get("type").asString().equals("INCOME") ? incomeCategoryId : expenseCategoryId);
            }
            rows.add(row);
        }
        assertThat(matched).isNotNull();
        assertThat(matched.get("amount").decimalValue()).isEqualByComparingTo("52.30");
        assertThat(matched.get("matchTransactionId").asLong()).isEqualTo(txId);
        assertThat(matched.get("matchItemCount").asInt()).isEqualTo(2);
        assertThat(matched.get("duplicate").asBoolean()).isFalse();

        MvcTestResult confirm = mvc.post().uri("/api/imports/confirm").headers(auth())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("accountId", accountId, "source", "OFX", "filename", "extrato.ofx", "rows", rows)))
            .exchange();
        assertThat(confirm).hasStatus(201);
        assertThat(body(confirm).get("linked").asInt()).isEqualTo(1);
        assertThat(body(confirm).get("imported").asInt()).isEqualTo(2);
        long batchId = body(confirm).get("batchId").asLong();

        JsonNode after = body(mvc.get().uri("/api/transactions/" + txId).headers(auth()).exchange());
        assertThat(after.get("externalId").asString()).isEqualTo(matched.get("externalId").asString());
        assertThat(after.get("importBatchId").asLong()).isEqualTo(batchId);
        assertThat(after.get("items")).hasSize(2);
        assertThat(after.get("description").asString()).isEqualTo("Padaria Pão Quente");

        // Importar o mesmo extrato de novo: a linha agora é duplicada (FITID já usado), não sugere vínculo.
        JsonNode again = body(mvc.post().uri("/api/imports/preview").headers(auth()).multipart()
            .file(file).param("accountId", String.valueOf(accountId)).exchange());
        assertThat(again.get("duplicates").asInt()).isEqualTo(3);

        // Excluir a importação apaga só os 2 criados por ela; o lançamento vinculado fica, sem o vínculo.
        JsonNode del = body(mvc.delete().uri("/api/imports/" + batchId).headers(auth()).exchange());
        assertThat(del.get("removed").asInt()).isEqualTo(2);
        JsonNode kept = body(mvc.get().uri("/api/transactions/" + txId).headers(auth()).exchange());
        assertThat(kept.get("importBatchId").isNull()).isTrue();
        assertThat(kept.get("externalId").isNull()).isTrue();
        assertThat(kept.get("items")).hasSize(2);

        // Leitura de comprovante com Ollama fora do ar responde 422 com mensagem clara; imagem alheia é 404.
        BufferedImageHolder.write();
        MockMultipartFile photo = new MockMultipartFile("file", "cupom.png", "image/png", BufferedImageHolder.PNG);
        MvcTestResult scan = mvc.post().uri("/api/receipts/scan").headers(auth()).multipart().file(photo).exchange();
        assertThat(scan).hasStatus(422);
        assertThat(body(scan).get("detail").asString()).contains("Ollama");
        assertThat(mvc.get().uri("/api/receipts/u999-" + java.util.UUID.randomUUID() + ".jpg").headers(auth()).exchange())
            .hasStatus(404);
    }

    /** PNG mínimo gerado em memória para o teste de upload. */
    static final class BufferedImageHolder {
        static byte[] PNG;

        static void write() throws java.io.IOException {
            var img = new java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            PNG = out.toByteArray();
        }
    }

    @Test
    @Order(7)
    void validationErrorsAreProblemDetails() {
        MvcTestResult res = mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"description\":\"\",\"amount\":-1}").exchange();
        assertThat(res).hasStatus(400);
        JsonNode pd = body(res);
        assertThat(pd.get("errors").has("description")).isTrue();
        assertThat(pd.get("errors").has("amount")).isTrue();
    }
}
