package br.com.contaslt.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.contaslt.common.TransactionType;

class OfxParserTest {

    @Test
    void parsesSgmlStatementWithBrazilianDecimalComma() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/samples/extrato.ofx").readAllBytes();
        List<ParsedRow> rows = new OfxParser().parse(bytes);
        assertThat(rows).hasSize(3);
        ParsedRow energia = rows.get(0);
        assertThat(energia.date()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(energia.amount()).isEqualByComparingTo(new BigDecimal("189.90"));
        assertThat(energia.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(energia.description()).isEqualTo("PAGTO ENEL DISTRIBUICAO");
        assertThat(energia.externalId()).isEqualTo("2026100300001");
        assertThat(rows.get(1).type()).isEqualTo(TransactionType.INCOME);
        assertThat(rows.get(2).description()).isEqualTo("IFOOD *RESTAURANTE");
    }

    @Test
    void parseAmountHandlesFormats() {
        assertThat(OfxParser.parseAmount("-1.234,56")).isEqualByComparingTo("-1234.56");
        assertThat(OfxParser.parseAmount("1,234.56")).isEqualByComparingTo("1234.56");
        assertThat(OfxParser.parseAmount("-50,00")).isEqualByComparingTo("-50.00");
        assertThat(OfxParser.parseAmount("4500.00")).isEqualByComparingTo("4500.00");
    }
}
