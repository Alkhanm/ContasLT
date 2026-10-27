package br.com.contaslt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import br.com.contaslt.receipt.ReceiptDtos.ScannedItem;
import br.com.contaslt.receipt.ReceiptScanner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Sem chave de acesso legível, um comprovante com a mesma data e o mesmo valor de um lançamento existente é apontado como duplicata. */
@SpringBootTest
@AutoConfigureMockMvc
class ReceiptDuplicateTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ObjectMapper json;

    @MockitoBean
    ReceiptScanner scanner;

    private String token;
    private long accountId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "dup-" + System.nanoTime() + "@contaslt.app";
        MvcTestResult reg = mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Dup", "email", email, "password", "segredo123"))).exchange();
        token = body(reg).get("token").asString();
        accountId = body(mvc.get().uri("/api/accounts").headers(auth()).exchange()).get(0).get("id").asLong();

        when(scanner.scan(any())).thenReturn(new ReceiptScan("SUPERMERCADO ALMEIDA", LocalDate.of(2026, 10, 25), null, true,
            new BigDecimal("31.97"), "Pix", null,
            List.of(new ScannedItem("BATATA LAYS CLASSICA 62G", new BigDecimal("2"), new BigDecimal("10.99"), new BigDecimal("21.98")),
                new ScannedItem("ENERGETICO EXTRA POWER ZE", BigDecimal.ONE, new BigDecimal("9.99"), new BigDecimal("9.99")))));
    }

    @Test
    void flagsSameDateAndAmountAsDuplicate() throws Exception {
        JsonNode first = body(scan(null));
        assertThat(first.get("duplicateOfTransactionId").isNull()).isTrue();
        assertThat(first.get("date").asString()).isEqualTo("2026-10-25");
        assertThat(first.get("paid").asBoolean()).isTrue();

        // Grava o lançamento a partir da primeira leitura.
        MvcTestResult created = mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("description", "SUPERMERCADO ALMEIDA", "amount", 31.97, "type", "EXPENSE",
                "dueDate", "2026-10-25", "paid", true, "accountId", accountId, "merchant", "SUPERMERCADO ALMEIDA",
                "receiptFile", first.get("receiptFile").asString()))).exchange();
        assertThat(created).hasStatus(201);
        long txId = body(created).get("id").asLong();

        // Mesmo cupom de novo: apontado como duplicata, mesmo sem chave de acesso.
        JsonNode second = body(scan(null));
        assertThat(second.get("duplicateOfTransactionId").asLong()).isEqualTo(txId);
        assertThat(second.get("duplicateOfDescription").asString()).isEqualTo("SUPERMERCADO ALMEIDA");
        assertThat(second.get("duplicateOfDate").asString()).isEqualTo("2026-10-25");

        // Relendo o comprovante do próprio lançamento em edição, ele não acusa a si mesmo.
        JsonNode editing = body(scan(txId));
        assertThat(editing.get("duplicateOfTransactionId").isNull()).isTrue();
    }

    @Test
    void billsUseDueDateAndComeUnpaid() throws Exception {
        when(scanner.scan(any())).thenReturn(new ReceiptScan("Equatorial Goiás", LocalDate.of(2026, 10, 22), LocalDate.of(2026, 11, 8), false,
            new BigDecimal("207.09"), "", null, List.of()));
        JsonNode bill = body(scan(null));
        assertThat(bill.get("date").asString()).isEqualTo("2026-11-08");
        assertThat(bill.get("paid").asBoolean()).isFalse();

        when(scanner.scan(any())).thenReturn(new ReceiptScan("", null, null, true, new BigDecimal("76.89"), "", null, List.of()));
        JsonNode undated = body(scan(null));
        assertThat(undated.get("date").asString()).isEqualTo(LocalDate.now().toString());
    }

    private MvcTestResult scan(Long ignoreId) throws Exception {
        var img = new java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        MockMultipartFile photo = new MockMultipartFile("file", "cupom.png", "image/png", out.toByteArray());
        var req = mvc.post().uri("/api/receipts/scan").headers(auth()).multipart().file(photo);
        if (ignoreId != null) {
            req = req.param("ignoreTransactionId", String.valueOf(ignoreId));
        }
        MvcTestResult res = req.exchange();
        assertThat(res).hasStatusOk();
        return res;
    }

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private JsonNode body(MvcTestResult res) throws Exception {
        return json.readTree(res.getResponse().getContentAsString());
    }
}
