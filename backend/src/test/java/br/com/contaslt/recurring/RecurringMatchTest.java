package br.com.contaslt.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import br.com.contaslt.receipt.ReceiptDtos.ReceiptScan;
import br.com.contaslt.receipt.ReceiptScanner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Extrato e comprovante casam com vencimentos pendentes de conta recorrente pelo nome, com data e valor dentro da precisão da regra. */
@SpringBootTest
@AutoConfigureMockMvc
class RecurringMatchTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ObjectMapper json;

    @MockitoBean
    ReceiptScanner scanner;

    private String token;
    private long accountId;

    @BeforeEach
    void setUp() {
        String email = "match" + System.nanoTime() + "@exemplo.com";
        token = body(mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Match", "email", email, "password", "segredo1")))
            .exchange()).get("token").asString();
        accountId = body(mvc.get().uri("/api/accounts").headers(auth()).exchange()).get(0).get("id").asLong();
    }

    @Test
    void statementRowSettlesPendingOccurrenceWithRealAmountAndDate() {
        long billId = bill("Internet LINQ", 99.99, "2035-03-01", null);

        // Boleto pago no dia 10 com multa: valor e data diferentes, mas o nome bate. O mercado não bate com nada.
        JsonNode pv = preview(ofx(
            row("2035-03-10", "-102.00", "Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA", "A1"),
            row("2035-03-10", "-102.00", "SUPERMERCADO ALMEIDA", "A2")));
        JsonNode linq = rowNamed(pv, "LINQ");
        JsonNode market = rowNamed(pv, "SUPERMERCADO");
        assertThat(linq.get("matchTransactionId").isNull()).isFalse();
        assertThat(linq.get("matchRecurring").asBoolean()).isTrue();
        assertThat(linq.get("matchDescription").asString()).isEqualTo("Internet LINQ");
        assertThat(linq.get("matchDate").asString()).isEqualTo("2035-03-01");
        assertThat(linq.get("matchAmount").decimalValue()).isEqualByComparingTo("99.99");
        assertThat(market.get("matchTransactionId").isNull()).isTrue();
        long occurrenceId = linq.get("matchTransactionId").asLong();

        JsonNode result = confirm(List.of(
            confirmRow(linq, occurrenceId),
            confirmRow(market, null)));
        assertThat(result.get("linked").asInt()).isEqualTo(1);
        assertThat(result.get("imported").asInt()).isEqualTo(1);

        // O vencimento virou o pagamento real: pago, com valor e data do extrato, e a data prevista guardada.
        JsonNode occ = body(mvc.get().uri("/api/transactions/" + occurrenceId).headers(auth()).exchange());
        assertThat(occ.get("paid").asBoolean()).isTrue();
        assertThat(occ.get("amount").decimalValue()).isEqualByComparingTo("102.00");
        assertThat(occ.get("dueDate").asString()).isEqualTo("2035-03-10");
        assertThat(occ.get("scheduledDate").asString()).isEqualTo("2035-03-01");
        assertThat(occ.get("expectedAmount").decimalValue()).isEqualByComparingTo("99.99");
        assertThat(occ.get("recurringBillId").asLong()).isEqualTo(billId);
        assertThat(occ.get("description").asString()).isEqualTo("Internet LINQ");

        // Março tem só o vencimento pago e o mercado: nada duplicado, e o gerador não recria o dia 1º.
        JsonNode march = body(mvc.get().uri("/api/transactions?month=2035-03").headers(auth()).exchange()).get("items");
        assertThat(march).hasSize(2);
        // Abril segue previsto com o valor estimado da regra.
        JsonNode april = body(mvc.get().uri("/api/transactions?month=2035-04").headers(auth()).exchange()).get("items");
        assertThat(april).hasSize(1);
        assertThat(april.get(0).get("amount").decimalValue()).isEqualByComparingTo("99.99");
    }

    @Test
    void strictnessControlsHowMuchDateAndAmountMayDrift() {
        bill("Energia Enel", 200.00, "2035-05-05", 10);
        bill("Água Saneago", 80.00, "2035-05-05", 1);

        JsonNode pv = preview(ofx(
            row("2035-05-12", "-204.00", "PAGTO ENEL DISTRIBUICAO", "B1"),
            row("2035-05-28", "-115.00", "SANEAGO SANEAMENTO DE GOIAS", "B2")));
        // Nível 10: só casaria com valor e data exatos.
        assertThat(rowNamed(pv, "ENEL").get("matchTransactionId").isNull()).isTrue();
        // Nível 1: praticamente só o nome, dentro do mês.
        assertThat(rowNamed(pv, "SANEAGO").get("matchTransactionId").isNull()).isFalse();
        assertThat(rowNamed(pv, "SANEAGO").get("matchDescription").asString()).isEqualTo("Água Saneago");

        JsonNode exact = preview(ofx(row("2035-05-05", "-200.00", "PAGTO ENEL DISTRIBUICAO", "B3")));
        assertThat(exact.get("rows").get(0).get("matchDescription").asString()).isEqualTo("Energia Enel");
    }

    @Test
    void previousPaymentsTeachHowTheBillAppearsOnStatements() {
        // Regra com nome "Internet" não compartilha palavra com o extrato; o pagamento anterior adotado sim.
        long txId = transaction("Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA", 99.99, "2035-07-20");
        Map<String, Object> b = billRequest("Internet", 99.99, "2035-07-20", null);
        b.put("fromTransactionId", txId);
        assertThat(mvc.post().uri("/api/recurring-bills").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(b)).exchange()).hasStatus(201);

        JsonNode pv = preview(ofx(row("2035-08-22", "-99.99", "Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA", "C1")));
        assertThat(pv.get("rows").get(0).get("matchDescription").asString()).isEqualTo("Internet");
        assertThat(pv.get("rows").get(0).get("matchDate").asString()).isEqualTo("2035-08-20");
    }

    @Test
    void receiptSuggestsThePendingOccurrenceItPays() throws Exception {
        long billId = bill("Internet LINQ", 99.99, "2035-09-01", null);
        when(scanner.scan(any())).thenReturn(new ReceiptScan("LINQ Telecomunicacoes", LocalDate.of(2035, 9, 9), null, true,
            new BigDecimal("101.50"), "Pix", null, List.of()));

        var img = new java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        MockMultipartFile photo = new MockMultipartFile("file", "cupom.png", "image/png", out.toByteArray());
        JsonNode scan = body(mvc.post().uri("/api/receipts/scan").headers(auth()).multipart().file(photo).exchange());

        assertThat(scan.get("duplicateOfTransactionId").isNull()).isTrue();
        assertThat(scan.get("recurringMatchTransactionId").isNull()).isFalse();
        assertThat(scan.get("recurringMatchDescription").asString()).isEqualTo("Internet LINQ");
        assertThat(scan.get("recurringMatchDate").asString()).isEqualTo("2035-09-01");
        assertThat(scan.get("recurringMatchAmount").decimalValue()).isEqualByComparingTo("99.99");
        assertThat(scan.get("recurringMatchAccountId").asLong()).isEqualTo(accountId);
        long occurrenceId = scan.get("recurringMatchTransactionId").asLong();

        // O app salva o comprovante por cima do vencimento (PUT), que fica pago com o valor real.
        Map<String, Object> m = new HashMap<>();
        m.put("description", "Internet LINQ");
        m.put("amount", 101.50);
        m.put("type", "EXPENSE");
        m.put("dueDate", "2035-09-09");
        m.put("paid", true);
        m.put("accountId", accountId);
        m.put("merchant", "LINQ Telecomunicacoes");
        m.put("receiptFile", scan.get("receiptFile").asString());
        assertThat(mvc.put().uri("/api/transactions/" + occurrenceId).headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(m)).exchange()).hasStatusOk();
        JsonNode sept = body(mvc.get().uri("/api/transactions?month=2035-09").headers(auth()).exchange()).get("items");
        assertThat(sept).hasSize(1);
        assertThat(sept.get(0).get("recurringBillId").asLong()).isEqualTo(billId);
        assertThat(sept.get(0).get("paid").asBoolean()).isTrue();
        assertThat(sept.get(0).get("scheduledDate").asString()).isEqualTo("2035-09-01");
    }

    private Map<String, Object> billRequest(String description, double amount, String startDate, Integer strictness) {
        Map<String, Object> m = new HashMap<>();
        m.put("description", description);
        m.put("amount", amount);
        m.put("type", "EXPENSE");
        m.put("frequency", "MONTHLY");
        m.put("startDate", startDate);
        m.put("accountId", accountId);
        m.put("matchStrictness", strictness);
        return m;
    }

    private long bill(String description, double amount, String startDate, Integer strictness) {
        MvcTestResult res = mvc.post().uri("/api/recurring-bills").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(billRequest(description, amount, startDate, strictness))).exchange();
        assertThat(res).hasStatus(201);
        return body(res).get("id").asLong();
    }

    private long transaction(String description, double amount, String dueDate) {
        Map<String, Object> m = new HashMap<>();
        m.put("description", description);
        m.put("amount", amount);
        m.put("type", "EXPENSE");
        m.put("dueDate", dueDate);
        m.put("paid", true);
        m.put("accountId", accountId);
        MvcTestResult res = mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(m)).exchange();
        assertThat(res).hasStatus(201);
        return body(res).get("id").asLong();
    }

    private static String row(String date, String amount, String memo, String fitid) {
        return "<STMTTRN>\n<TRNTYPE>" + (amount.startsWith("-") ? "DEBIT" : "CREDIT") + "\n<DTPOSTED>" + date.replace("-", "")
            + "\n<TRNAMT>" + amount + "\n<FITID>" + fitid + "\n<MEMO>" + memo + "\n</STMTTRN>\n";
    }

    private static byte[] ofx(String... rows) {
        StringBuilder sb = new StringBuilder("OFXHEADER:100\nDATA:OFXSGML\nVERSION:102\nSECURITY:NONE\nENCODING:USASCII\n"
            + "CHARSET:1252\nCOMPRESSION:NONE\nOLDFILEUID:NONE\nNEWFILEUID:NONE\n\n<OFX>\n<BANKMSGSRSV1>\n<STMTTRNRS>\n<STMTRS>\n"
            + "<CURDEF>BRL\n<BANKTRANLIST>\n");
        for (String r : rows) {
            sb.append(r);
        }
        sb.append("</BANKTRANLIST>\n</STMTRS>\n</STMTTRNRS>\n</BANKMSGSRSV1>\n</OFX>\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private JsonNode preview(byte[] ofx) {
        MockMultipartFile file = new MockMultipartFile("file", "extrato.ofx", "application/x-ofx", ofx);
        MvcTestResult res = mvc.post().uri("/api/imports/preview").headers(auth()).multipart()
            .file(file).param("accountId", String.valueOf(accountId)).exchange();
        assertThat(res).hasStatusOk();
        return body(res);
    }

    private static JsonNode rowNamed(JsonNode preview, String fragment) {
        for (JsonNode r : preview.get("rows")) {
            if (r.get("description").asString().contains(fragment)) {
                return r;
            }
        }
        throw new AssertionError("linha não encontrada: " + fragment);
    }

    private Map<String, Object> confirmRow(JsonNode r, Long linkId) {
        Map<String, Object> m = new HashMap<>();
        m.put("date", r.get("date").asString());
        m.put("description", r.get("description").asString());
        m.put("amount", r.get("amount").decimalValue());
        m.put("type", r.get("type").asString());
        m.put("externalId", r.get("externalId").asString());
        m.put("linkTransactionId", linkId);
        return m;
    }

    private JsonNode confirm(List<Map<String, Object>> rows) {
        MvcTestResult res = mvc.post().uri("/api/imports/confirm").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("accountId", accountId, "source", "OFX", "filename", "extrato.ofx",
                "rows", new ArrayList<>(rows)))).exchange();
        assertThat(res).hasStatus(201);
        return body(res);
    }

    private JsonNode body(MvcTestResult result) {
        try {
            return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
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
