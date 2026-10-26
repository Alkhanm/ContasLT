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

    private final ReceiptScanner scanner = new ReceiptScanner(new AppProperties(null, null,
        new AppProperties.Ollama("http://localhost:1", "test", Duration.ofSeconds(1)), new AppProperties.Storage("/tmp"), null),
        new ObjectMapper());

    @Test
    void parsesModelJsonToleratingFormats() throws Exception {
        var parse = ReceiptScanner.class.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        ReceiptScan scan = (ReceiptScan) parse.invoke(scanner, """
            {"merchant":"ASSAI ATACADISTA","date":"17/10/2026","total":"177,60","paymentMethod":"Cartao de Debito",
             "accessKey":"2226 1006 0572 2303 5115 6500 1000 4521 7912 3456 7890",
             "items":[{"description":"LEITE 1L","quantity":6,"unitPrice":4.79,"total":28.74},
                      {"description":"","quantity":1,"unitPrice":1,"total":1},
                      {"description":"BANANA KG","quantity":"1,235","unitPrice":"6,99","total":"8,63"}]}
            """);
        assertThat(scan.merchant()).isEqualTo("ASSAI ATACADISTA");
        assertThat(scan.date()).isEqualTo("2026-10-17");
        assertThat(scan.total()).isEqualByComparingTo("177.60");
        assertThat(scan.accessKey()).hasSize(44).doesNotContain(" ");
        assertThat(scan.items()).hasSize(2);
        assertThat(scan.items().get(1).quantity()).isEqualByComparingTo("1.235");
        assertThat(scan.items().get(1).total()).isEqualByComparingTo("8.63");
    }

    @Test
    void emptyOrOddValuesBecomeNull() throws Exception {
        var parse = ReceiptScanner.class.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        ReceiptScan scan = (ReceiptScan) parse.invoke(scanner, "{\"merchant\":\"X\",\"date\":\"\",\"total\":null,\"accessKey\":\"123\",\"items\":[]}");
        assertThat(scan.date()).isNull();
        assertThat(scan.total()).isNull();
        assertThat(scan.accessKey()).isNull();
        assertThat(scan.items()).isEqualTo(List.of());
    }
}
