package br.com.contaslt.card;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** Cartão de crédito: compras fora das listas, fatura como recorte, pagamento como despesa que agrega as compras. */
@SpringBootTest
@AutoConfigureMockMvc
class CardInvoiceTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ObjectMapper json;

    private String token;
    private long checkingId;
    private long cardId;

    @BeforeEach
    void setUp() {
        token = body(mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Cartao", "email", "card" + System.nanoTime() + "@exemplo.com",
                "password", "segredo1"))).exchange()).get("token").asString();
        checkingId = body(mvc.get().uri("/api/accounts").headers(auth()).exchange()).get(0).get("id").asLong();
        // Fecha dia 25, vence dia 5 do mês seguinte.
        MvcTestResult created = mvc.post().uri("/api/accounts").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Nubank", "type", "CREDIT_CARD", "initialBalance", 0,
                "color", "#820AD1", "closingDay", 25, "dueDay", 5))).exchange();
        assertThat(created).hasStatus(201);
        cardId = body(created).get("id").asLong();
        assertThat(body(created).get("closingDay").asInt()).isEqualTo(25);
    }

    @Test
    void cardPurchasesStayOutOfTheMainListAndGroupByInvoicePeriod() {
        transaction(cardId, "Mercado 25/09", 100, "EXPENSE", "2034-09-25");   // fecha em 25/09 → vence 05/10
        transaction(cardId, "Farmácia 26/09", 50, "EXPENSE", "2034-09-26");   // já é da fatura de novembro
        transaction(cardId, "Posto 10/09", 200, "EXPENSE", "2034-09-10");
        transaction(checkingId, "Padaria", 20, "EXPENSE", "2034-09-10");

        // Lista geral: só a conta de dinheiro; compras do cartão não aparecem nem contam como despesa.
        JsonNode all = body(mvc.get().uri("/api/transactions?month=2034-09").headers(auth()).exchange());
        assertThat(all.get("items")).hasSize(1);
        assertThat(all.get("summary").get("expense").decimalValue()).isEqualByComparingTo("20");
        assertThat(all.get("summary").get("balance").decimalValue()).isEqualByComparingTo("-20");

        // Visão do cartão por fatura: compras entre dois fechamentos.
        JsonNode page = body(mvc.get().uri("/api/transactions?month=2034-10&accountId=" + cardId + "&invoice=true")
            .headers(auth()).exchange());
        assertThat(page.get("items")).hasSize(2);
        JsonNode inv = page.get("invoice");
        assertThat(inv.get("month").asString()).isEqualTo("2034-10");
        assertThat(inv.get("start").asString()).isEqualTo("2034-08-26");
        assertThat(inv.get("end").asString()).isEqualTo("2034-09-25");
        assertThat(inv.get("dueDate").asString()).isEqualTo("2034-10-05");
        assertThat(inv.get("purchases").decimalValue()).isEqualByComparingTo("300");
        assertThat(inv.get("count").asInt()).isEqualTo(2);
        assertThat(inv.get("paid").decimalValue()).isEqualByComparingTo("0");
        assertThat(inv.get("status").asString()).isEqualTo("OPEN"); // datas no futuro: ainda não fechou

        // Sem "invoice", a visão do cartão volta a ser o mês civil.
        JsonNode plain = body(mvc.get().uri("/api/transactions?month=2034-09&accountId=" + cardId).headers(auth()).exchange());
        assertThat(plain.get("items")).hasSize(3);
        assertThat(plain.get("invoice").isNull()).isTrue();

        // Saldo do cartão = dívida em aberto (nenhuma fatura paga ainda).
        assertThat(cardBalance()).isEqualByComparingTo("-350");
    }

    @Test
    void invoicePaymentIsTheExpenseAndAggregatesThePurchases() {
        transaction(cardId, "Mercado", 300, "EXPENSE", "2034-09-10");
        transaction(cardId, "Farmácia nov", 50, "EXPENSE", "2034-09-26"); // fatura de novembro, segue em aberto

        // Paga a fatura de outubro (R$ 500) como despesa da conta corrente ligada ao cartão.
        Map<String, Object> m = new HashMap<>();
        m.put("description", "Fatura Nubank");
        m.put("amount", 500);
        m.put("type", "EXPENSE");
        m.put("dueDate", "2034-10-05");
        m.put("paid", true);
        m.put("accountId", checkingId);
        m.put("invoiceCardId", cardId);
        MvcTestResult paid = mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(m)).exchange();
        assertThat(paid).hasStatus(201);
        JsonNode dto = body(paid);
        assertThat(dto.get("invoiceCardId").asLong()).isEqualTo(cardId);
        assertThat(dto.get("invoiceMonth").asString()).isEqualTo("2034-10"); // inferido pelo vencimento mais próximo
        assertThat(dto.get("invoice").get("purchases").decimalValue()).isEqualByComparingTo("300");
        assertThat(dto.get("invoice").get("count").asInt()).isEqualTo(1);
        assertThat(dto.get("invoice").get("undetailed").decimalValue()).isEqualByComparingTo("200");
        assertThat(dto.get("invoice").get("status").asString()).isEqualTo("PAID");

        // Na lista geral de outubro aparece só a fatura, como despesa de 500, com o resumo agregado.
        JsonNode oct = body(mvc.get().uri("/api/transactions?month=2034-10").headers(auth()).exchange());
        assertThat(oct.get("items")).hasSize(1);
        assertThat(oct.get("items").get(0).get("invoice").get("count").asInt()).isEqualTo(1);
        assertThat(oct.get("summary").get("expense").decimalValue()).isEqualByComparingTo("500");

        // Cartão: a fatura de outubro foi paga; só a compra de novembro continua devida.
        assertThat(cardBalance()).isEqualByComparingTo("-50");
        JsonNode inv = body(mvc.get().uri("/api/accounts/" + cardId + "/invoices?month=2034-10").headers(auth()).exchange());
        assertThat(inv.get("paid").decimalValue()).isEqualByComparingTo("500");
        assertThat(inv.get("paymentTransactionId").asLong()).isEqualTo(dto.get("id").asLong());

        // Comprovante lido depois entra na fatura e reduz o "não detalhado".
        transaction(cardId, "Restaurante", 120, "EXPENSE", "2034-09-20");
        inv = body(mvc.get().uri("/api/accounts/" + cardId + "/invoices?month=2034-10").headers(auth()).exchange());
        assertThat(inv.get("purchases").decimalValue()).isEqualByComparingTo("420");
        assertThat(inv.get("undetailed").decimalValue()).isEqualByComparingTo("80");
        assertThat(cardBalance()).isEqualByComparingTo("-50");

        // Pagar a fatura a partir do próprio cartão não faz sentido.
        m.put("accountId", cardId);
        assertThat(mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(m)).exchange()).hasStatus(422);
    }

    @Test
    void statementLineThatPaysTheInvoiceBecomesLinkedExpense() {
        transaction(cardId, "Mercado", 300, "EXPENSE", "2034-09-10");
        byte[] ofx = ofx(row("2034-10-05", "-480.00", "PAGAMENTO FATURA NUBANK", "F1"),
            row("2034-10-06", "-35.90", "PADARIA", "F2"));

        MockMultipartFile file = new MockMultipartFile("file", "extrato.ofx", "application/x-ofx", ofx);
        JsonNode preview = body(mvc.post().uri("/api/imports/preview").headers(auth()).multipart()
            .file(file).param("accountId", String.valueOf(checkingId)).exchange());
        JsonNode line = rowNamed(preview, "FATURA");
        assertThat(line.get("invoiceCardId").asLong()).isEqualTo(cardId);
        assertThat(line.get("invoiceCardName").asString()).isEqualTo("Nubank");
        assertThat(line.get("invoiceMonth").asString()).isEqualTo("2034-10");
        assertThat(line.get("invoiceDueDate").asString()).isEqualTo("2034-10-05");
        assertThat(line.get("invoiceTotal").decimalValue()).isEqualByComparingTo("300");
        assertThat(rowNamed(preview, "PADARIA").get("invoiceCardId").isNull()).isTrue();

        Map<String, Object> confirm = new HashMap<>();
        confirm.put("accountId", checkingId);
        confirm.put("source", "OFX");
        confirm.put("filename", "extrato.ofx");
        Map<String, Object> r1 = new HashMap<>();
        r1.put("date", "2034-10-05");
        r1.put("description", "PAGAMENTO FATURA NUBANK");
        r1.put("amount", 480.00);
        r1.put("type", "EXPENSE");
        r1.put("externalId", "F1");
        r1.put("invoiceCardId", cardId);
        r1.put("invoiceMonth", "2034-10");
        Map<String, Object> r2 = new HashMap<>();
        r2.put("date", "2034-10-06");
        r2.put("description", "PADARIA");
        r2.put("amount", 35.90);
        r2.put("type", "EXPENSE");
        r2.put("externalId", "F2");
        confirm.put("rows", java.util.List.of(r1, r2));
        MvcTestResult res = mvc.post().uri("/api/imports/confirm").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(confirm)).exchange();
        assertThat(res).hasStatus(201);
        assertThat(body(res).get("invoicePayments").asInt()).isEqualTo(1);

        // A fatura é despesa da conta corrente (480 + 35,90) e agrega a compra do cartão.
        JsonNode checking = body(mvc.get().uri("/api/transactions?month=2034-10&accountId=" + checkingId).headers(auth()).exchange());
        assertThat(checking.get("summary").get("expense").decimalValue()).isEqualByComparingTo("515.90");
        JsonNode inv = body(mvc.get().uri("/api/accounts/" + cardId + "/invoices?month=2034-10").headers(auth()).exchange());
        assertThat(inv.get("paid").decimalValue()).isEqualByComparingTo("480");
        assertThat(inv.get("undetailed").decimalValue()).isEqualByComparingTo("180");
        assertThat(inv.get("status").asString()).isEqualTo("PAID");
        assertThat(cardBalance()).isEqualByComparingTo("0");

        // OFX do próprio cartão: "Pagamento recebido" é sinalizado para não virar receita nem estorno.
        byte[] cardOfx = ofx(row("2034-10-05", "480.00", "Pagamento recebido", "C1"),
            row("2034-09-12", "-45.00", "IFOOD", "C2"));
        MockMultipartFile cardFile = new MockMultipartFile("file", "cartao.ofx", "application/x-ofx", cardOfx);
        JsonNode cardPreview = body(mvc.post().uri("/api/imports/preview").headers(auth()).multipart()
            .file(cardFile).param("accountId", String.valueOf(cardId)).exchange());
        assertThat(rowNamed(cardPreview, "Pagamento recebido").get("invoicePaymentReceived").asBoolean()).isTrue();
        assertThat(rowNamed(cardPreview, "IFOOD").get("invoicePaymentReceived").asBoolean()).isFalse();
    }

    private java.math.BigDecimal cardBalance() {
        for (JsonNode a : body(mvc.get().uri("/api/accounts").headers(auth()).exchange())) {
            if (a.get("id").asLong() == cardId) {
                return a.get("balance").decimalValue();
            }
        }
        throw new AssertionError("cartão não encontrado");
    }

    private long transaction(long accountId, String description, double amount, String type, String dueDate) {
        Map<String, Object> m = new HashMap<>();
        m.put("description", description);
        m.put("amount", amount);
        m.put("type", type);
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

    private static JsonNode rowNamed(JsonNode preview, String fragment) {
        for (JsonNode r : preview.get("rows")) {
            if (r.get("description").asString().contains(fragment)) {
                return r;
            }
        }
        throw new AssertionError("linha não encontrada: " + fragment);
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
