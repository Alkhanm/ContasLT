package br.com.contaslt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.contaslt.config.AppProperties;
import br.com.contaslt.receipt.ReceiptDtos.ReceiptScan;
import br.com.contaslt.receipt.ReceiptScanner;
import tools.jackson.databind.ObjectMapper;

class ReceiptScannerTest {

    private final ReceiptScanner scanner = new ReceiptScanner(new AppProperties(null, null, null,
        new AppProperties.Ollama("http://localhost:1", "test", Duration.ofSeconds(1)), null, new AppProperties.Storage("/tmp", null), null),
        new ObjectMapper());

    @Test
    void parsesModelJsonToleratingFormats() throws Exception {
        var parse = ReceiptScanner.class.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        ReceiptScan scan = (ReceiptScan) parse.invoke(scanner, """
            {"merchant":"ASSAI ATACADISTA","issueDate":"17/10/2026","dueDate":"","paid":true,"total":"177,60","paymentMethod":"Cartao de Debito",
             "accessKey":"5226 1037 2605 0200 0161 6501 1000 6984 7919 3360 1663",
             "items":[{"description":"LEITE 1L","quantity":6,"unitPrice":4.79,"total":28.74},
                      {"description":"","quantity":1,"unitPrice":1,"total":1},
                      {"description":"BANANA KG","quantity":"1,235","unitPrice":"6,99","total":"8,63"}]}
            """);
        assertThat(scan.merchant()).isEqualTo("ASSAI ATACADISTA");
        assertThat(scan.issueDate()).isEqualTo("2026-10-17");
        assertThat(scan.dueDate()).isNull();
        assertThat(scan.paid()).isTrue();
        assertThat(scan.total()).isEqualByComparingTo("177.60");
        assertThat(scan.accessKey()).hasSize(44).doesNotContain(" ");
        assertThat(scan.items()).hasSize(2);
        assertThat(scan.items().get(1).quantity()).isEqualByComparingTo("1.235");
        assertThat(scan.items().get(1).total()).isEqualByComparingTo("8.63");
    }

    @Test
    void billsCarryDueDateAndUnpaidFlag() throws Exception {
        var parse = ReceiptScanner.class.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        ReceiptScan scan = (ReceiptScan) parse.invoke(scanner, """
            {"merchant":"Equatorial Goiás","issueDate":"2026-10-22","dueDate":"2026-11-08","paid":false,
             "total":207.09,"paymentMethod":"","accessKey":"","items":[]}
            """);
        assertThat(scan.issueDate()).isEqualTo("2026-10-22");
        assertThat(scan.dueDate()).isEqualTo("2026-11-08");
        assertThat(scan.paid()).isFalse();
        assertThat(scan.items()).isEmpty();
    }

    @Test
    void emptyOrOddValuesBecomeNull() throws Exception {
        var parse = ReceiptScanner.class.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        ReceiptScan scan = (ReceiptScan) parse.invoke(scanner, "{\"merchant\":\"X\",\"issueDate\":\"\",\"total\":null,\"accessKey\":\"123\",\"items\":[]}");
        assertThat(scan.issueDate()).isNull();
        assertThat(scan.paid()).isTrue(); // sem o campo, assume comprovante de compra
        assertThat(scan.total()).isNull();
        assertThat(scan.accessKey()).isNull();
        assertThat(scan.items()).isEqualTo(List.of());
    }
}
