package br.com.contaslt.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Test;

/** Porte de frontend/src/domain/ofx.test.ts (OfxParserTest). */
public class OfxTest {

    /** Amostra do backend: procura a partir do diretório de trabalho do teste. */
    static byte[] sample() throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path p = dir.resolve("backend/src/test/resources/samples/extrato.ofx");
            if (Files.exists(p)) {
                return Files.readAllBytes(p);
            }
            dir = dir.getParent();
        }
        throw new IOException("extrato.ofx não encontrado");
    }

    @Test
    public void leExtratoSgmlComVirgulaDecimalBrasileira() throws IOException {
        List<ParsedRow> rows = Ofx.parseOfx(sample());
        assertEquals(3, rows.size());
        ParsedRow energia = rows.get(0);
        assertEquals("2026-10-03", energia.date);
        assertEquals(18990, energia.amount);
        assertEquals("EXPENSE", energia.type);
        assertEquals("PAGTO ENEL DISTRIBUICAO", energia.description);
        assertEquals("2026100300001", energia.externalId);
        assertEquals("INCOME", rows.get(1).type);
        assertEquals("IFOOD *RESTAURANTE", rows.get(2).description);
    }

    @Test
    public void interpretaOsFormatosDeValorEmCentavos() {
        assertEquals(Long.valueOf(-123456), Ofx.parseAmount("-1.234,56"));
        assertEquals(Long.valueOf(123456), Ofx.parseAmount("1,234.56"));
        assertEquals(Long.valueOf(-5000), Ofx.parseAmount("-50,00"));
        assertEquals(Long.valueOf(450000), Ofx.parseAmount("4500.00"));
        assertEquals(Long.valueOf(-1001), Ofx.parseAmount("−10,005"));
        assertNull(Ofx.parseAmount("abc"));
    }

    @Test
    public void xml2EmUtf8ComTagsFechadasEAcentos() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><OFX><BANKTRANLIST>"
            + "<STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>20260105</DTPOSTED><TRNAMT>-12.50</TRNAMT><FITID>X1</FITID>"
            + "<NAME>Padaria   São   João</NAME></STMTTRN></BANKTRANLIST></OFX>";
        List<ParsedRow> rows = Ofx.parseOfx(xml.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, rows.size());
        ParsedRow r = rows.get(0);
        assertEquals("2026-01-05", r.date);
        assertEquals("Padaria São João", r.description);
        assertEquals(1250, r.amount);
        assertEquals("EXPENSE", r.type);
        assertEquals("X1", r.externalId);
        assertNull(r.categoryHint);
    }

    @Test
    public void charset1252DecodificaComoWindows1252() {
        String text = "OFXHEADER:100\nCHARSET:1252\n<OFX>\n<STMTTRN>\n<DTPOSTED>20260105\n<TRNAMT>10\n<MEMO>CAFÉ\n</STMTTRN>\n</OFX>";
        List<ParsedRow> rows = Ofx.parseOfx(text.getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("CAFÉ", rows.get(0).description);
        assertEquals("INCOME", rows.get(0).type);
        assertNull(rows.get(0).externalId);
    }

    @Test
    public void semCabecalhoBytesInvalidosEmUtf8CaemParaWindows1252() {
        byte[] bytes = "<OFX><STMTTRN><DTPOSTED>20260105<TRNAMT>-3,50<MEMO>PÃO \u0080\n</STMTTRN></OFX>"
            .getBytes(StandardCharsets.ISO_8859_1);
        // "Ã" seguido de "O" não é UTF-8 válido: decodifica como windows-1252 (0x80 = €).
        assertEquals("PÃO €", Ofx.parseOfx(bytes).get(0).description);
    }

    @Test
    public void recusaArquivoQueNaoEOfxOuSemLancamentos() {
        ParseException e = assertThrows(ParseException.class, () -> Ofx.parseOfx("oi".getBytes(StandardCharsets.UTF_8)));
        assertEquals("O arquivo não parece ser um extrato OFX válido", e.getMessage());
        e = assertThrows(ParseException.class, () -> Ofx.parseOfx("<OFX></OFX>".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Nenhum lançamento encontrado no arquivo OFX", e.getMessage());
    }
}
