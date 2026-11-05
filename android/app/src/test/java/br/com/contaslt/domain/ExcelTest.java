package br.com.contaslt.domain;

import static br.com.contaslt.domain.SpreadsheetFixtures.row;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import br.com.contaslt.domain.SpreadsheetFixtures.Biff;
import br.com.contaslt.domain.SpreadsheetFixtures.DateCell;
import br.com.contaslt.domain.SpreadsheetFixtures.Inline;

/** Porte de frontend/src/domain/excel.test.ts (ExcelParserTest), mais casos do leitor .xls/.xlsx próprio. */
public class ExcelTest {

    private static void assertRow(ParsedRow r, String date, String description, long amount, String type,
                                  String categoryHint) {
        assertEquals(date, r.date);
        assertEquals(description, r.description);
        assertEquals(amount, r.amount);
        assertEquals(type, r.type);
        assertNull(r.externalId);
        assertEquals(categoryHint, r.categoryHint);
    }

    @Test
    public void achaOCabecalhoDepoisDeLinhasDeTituloELeTipoECategoria() {
        byte[] bytes = SpreadsheetFixtures.xlsx(SpreadsheetFixtures.rows(
            row("Extrato da conta"),
            row(),
            row("Data", "Histórico", "Valor (R$)", "Tipo", "Categoria"),
            row(new DateCell("2026-03-05"), "Supermercado   Bom   Preço", -120.555, null, "Mercado"),
            row("06/03/2026", "Salário", "4.500,00", "Crédito", ""),
            row("7/3/2026", "Estorno", "R$ (30,00)", null, null),
            row("08/03/26", "Taxa", "15,90", "Débito", null),
            row("2026-03-09", "Zero", 0, null, null),
            row("sem data", "Ignorada", 10, null, null)));
        List<ParsedRow> rows = Excel.parseExcel(bytes);
        assertEquals(4, rows.size());
        assertRow(rows.get(0), "2026-03-05", "Supermercado Bom Preço", 12056, "EXPENSE", "Mercado");
        assertRow(rows.get(1), "2026-03-06", "Salário", 450000, "INCOME", null);
        assertRow(rows.get(2), "2026-03-07", "Estorno", 3000, "EXPENSE", null);
        assertRow(rows.get(3), "2026-03-08", "Taxa", 1590, "EXPENSE", null);
    }

    @Test
    public void semCabecalhoAssumeDataDescricaoValorTambemEmXls() {
        List<ParsedRow> rows = Excel.parseExcel(SpreadsheetFixtures.xlsx(SpreadsheetFixtures.rows(row("31/02/2026", "", 10))));
        // Dia além do fim do mês vira o último dia (como o java.time); descrição vazia vira "Lançamento".
        assertEquals(1, rows.size());
        assertRow(rows.get(0), "2026-02-28", "Lançamento", 1000, "INCOME", null);

        // O mesmo em .xls (BIFF8), fluxo pequeno (no mini-stream do OLE2).
        Biff cells = new Biff();
        cells.record(0x0200, SpreadsheetFixtures.data().i32(0).i32(1).u16(0).u16(3).u16(0).bytes());
        cells.record(0x00FD, SpreadsheetFixtures.data().u16(0).u16(0).u16(0).i32(0).bytes());
        cells.record(0x00FD, SpreadsheetFixtures.data().u16(0).u16(1).u16(0).i32(1).bytes());
        cells.record(0x027E, SpreadsheetFixtures.data().u16(0).u16(2).u16(0).i32(SpreadsheetFixtures.rkInt(10, false)).bytes());
        byte[] xls = SpreadsheetFixtures.ole2(SpreadsheetFixtures.workbook(SpreadsheetFixtures.sst("31/02/2026", ""), cells, 0));
        rows = Excel.parseExcel(xls);
        assertEquals(1, rows.size());
        assertRow(rows.get(0), "2026-02-28", "Lançamento", 1000, "INCOME", null);
    }

    @Test
    public void cabecalhoSemColunaDeDescricaoUsaAPrimeiraColunaLivre() {
        List<ParsedRow> rows = Excel.parseExcel(SpreadsheetFixtures.xlsx(SpreadsheetFixtures.rows(
            row("Valor", "Data", "Loja"), row(-5, "01/01/2026", "Banca"))));
        assertEquals("Banca", rows.get(0).description);
        assertEquals(500, rows.get(0).amount);
    }

    @Test
    public void mensagensDeErro() {
        ParseException e = assertThrows(ParseException.class,
            () -> Excel.parseExcel(SpreadsheetFixtures.xlsx(SpreadsheetFixtures.rows(row("Data", "Descrição", "Valor")))));
        assertEquals("Nenhuma linha com data e valor foi encontrada na planilha. Use colunas Data, Descrição e Valor.",
            e.getMessage());
        e = assertThrows(ParseException.class,
            () -> Excel.parseExcel("não é planilha".getBytes(StandardCharsets.UTF_8)));
        assertTrue(e.getMessage(), e.getMessage().startsWith("Não foi possível ler a planilha: "));
        e = assertThrows(ParseException.class,
            () -> Excel.parseExcel(new byte[] {0x50, 0x4b, 3, 4, 0, 0, 0, 0, 0, 0}));
        assertTrue(e.getMessage(), e.getMessage().startsWith("Não foi possível ler a planilha: "));
    }

    @Test
    public void xlsxComTextoEmLinhaBooleanoSemDimensionESistema1904() {
        byte[] bytes = SpreadsheetFixtures.xlsx(SpreadsheetFixtures.rows(
            row(new Inline("Data"), new Inline("Descrição"), new Inline("Valor"), new Inline("Pago")),
            row(new DateCell("2026-04-10"), new Inline("Café & pão"), -7.5, true)), true, false);
        List<ParsedRow> rows = Excel.parseExcel(bytes);
        assertEquals(1, rows.size());
        assertRow(rows.get(0), "2026-04-10", "Café & pão", 750, "EXPENSE", null);
    }

    @Test
    public void xlsDeBiff8ComFormatosRkMulrkFormulasEContinue() {
        // SST com o texto "Padaria São João" dividido num CONTINUE (primeira metade compactada, resto em UTF-16).
        SpreadsheetFixtures.Data first = SpreadsheetFixtures.data().i32(5).i32(5)
            .unicode("Data").unicode("Descrição").unicode("Valor").unicode("Tipo");
        String split = "Padaria São João ☕";
        first.u16(split.length()).u8(0).chars(split.substring(0, 8), false);
        byte[] cont = SpreadsheetFixtures.data().u8(1).chars(split.substring(8), true).bytes();
        List<byte[]> sst = new ArrayList<>(Arrays.asList(first.bytes(), cont));

        Biff c = new Biff();
        c.record(0x0200, SpreadsheetFixtures.data().i32(0).i32(5).u16(0).u16(4).u16(0).bytes());
        for (int col = 0; col < 4; col++) {
            c.record(0x00FD, SpreadsheetFixtures.data().u16(0).u16(col).u16(0).i32(col).bytes());
        }
        // Linha 1: data numérica no formato dd/mm/yyyy (XF 1), texto do SST, valor RK com /100 e tipo LABEL.
        double serial = java.time.LocalDate.parse("2026-05-02").toEpochDay() + 25569;
        c.record(0x0203, SpreadsheetFixtures.data().u16(1).u16(0).u16(1).f64(serial).bytes());
        c.record(0x00FD, SpreadsheetFixtures.data().u16(1).u16(1).u16(0).i32(4).bytes());
        c.record(0x027E, SpreadsheetFixtures.data().u16(1).u16(2).u16(0).i32(SpreadsheetFixtures.rkInt(1234, true)).bytes());
        c.record(0x0204, SpreadsheetFixtures.data().u16(1).u16(3).u16(0).unicode("Débito").bytes());
        // Linha 2: data embutida m/d/yy (XF 2), descrição por fórmula com texto guardado (STRING), valor por fórmula.
        double serial2 = java.time.LocalDate.parse("2026-05-03").toEpochDay() + 25569;
        c.record(0x00BD, SpreadsheetFixtures.data().u16(2).u16(0).u16(2).i32(rkDouble(serial2)).u16(3).bytes());
        c.record(0x0006, SpreadsheetFixtures.data().u16(2).u16(1).u16(0)
            .u8(0).u8(0).u8(0).u8(0).u8(0).u8(0).u16(0xFFFF).u16(0).i32(0).u16(0).bytes());
        c.record(0x0207, SpreadsheetFixtures.data().unicode("Salário (fórmula)").bytes());
        c.record(0x0006, SpreadsheetFixtures.data().u16(2).u16(2).u16(0).f64(2500.75).u16(0).i32(0).u16(0).bytes());
        // Linha 3: MULRK com data (XF 1) e descrição numérica (vira texto), RK negativo e booleano na coluna de tipo.
        double serial3 = java.time.LocalDate.parse("2026-05-04").toEpochDay() + 25569;
        c.record(0x00BD, SpreadsheetFixtures.data().u16(3).u16(0).u16(1).i32(rkDouble(serial3))
            .u16(0).i32(SpreadsheetFixtures.rkInt(42, false)).u16(1).bytes());
        c.record(0x027E, SpreadsheetFixtures.data().u16(3).u16(2).u16(0).i32(SpreadsheetFixtures.rkInt(-9, false)).bytes());
        c.record(0x0205, SpreadsheetFixtures.data().u16(3).u16(3).u16(0).u8(1).u8(0).bytes());
        // Linha 4: número sem formato de data na coluna de data: ignorada.
        c.record(0x0203, SpreadsheetFixtures.data().u16(4).u16(0).u16(0).f64(46000).bytes());
        c.record(0x0203, SpreadsheetFixtures.data().u16(4).u16(2).u16(0).f64(1).bytes());

        // Com enchimento: o fluxo passa de 4096 bytes e fica nos setores comuns do OLE2.
        byte[] xls = SpreadsheetFixtures.ole2(SpreadsheetFixtures.workbook(sst, c, 6000));
        List<ParsedRow> rows = Excel.parseExcel(xls);
        assertEquals(3, rows.size());
        assertRow(rows.get(0), "2026-05-02", "Padaria São João ☕", 1234, "EXPENSE", null);
        assertRow(rows.get(1), "2026-05-03", "Salário (fórmula)", 250075, "INCOME", null);
        assertRow(rows.get(2), "2026-05-04", "42", 900, "EXPENSE", null);
    }

    @Test
    public void xlsProtegidoPorSenhaOuAntigoDaErroLegivel() {
        Biff c = new Biff();
        byte[] wb = SpreadsheetFixtures.workbook(SpreadsheetFixtures.sst(), c, 0);
        // Troca o CODEPAGE por FILEPASS.
        wb[16 + 4] = 0x2F;
        wb[16 + 5] = 0x00;
        ParseException e = assertThrows(ParseException.class, () -> Excel.parseExcel(SpreadsheetFixtures.ole2(wb)));
        assertEquals("Não foi possível ler a planilha: a planilha está protegida por senha", e.getMessage());
    }

    @Test
    public void numerosDeSerieComoOSheetJs() {
        assertEquals("1900-02-28", Ofx.smartDate(1900, 2, 29));
        int[] d = Excel.parseDateCode(60, false);
        assertEquals(1900, d[0]);
        assertEquals(2, d[1]);
        assertEquals(29, d[2]);
        d = Excel.parseDateCode(46086.9999999999, false);
        assertEquals(3, d[1]);
        assertEquals(6, d[2]);
        d = Excel.parseDateCode(0, true);
        assertEquals(1904, d[0]);
        assertEquals(1, d[1]);
        assertEquals(1, d[2]);
        assertTrue(Excel.isDateFormat("dd/mm/yyyy"));
        assertTrue(Excel.isDateFormat("[$-416]d mmm yyyy"));
        assertTrue(Excel.isDateFormat("[h]:mm"));
        assertTrue(!Excel.isDateFormat("#,##0.00"));
        assertTrue(!Excel.isDateFormat("\"R$\" #,##0.00;[Red]-\"R$\" #,##0.00"));
        assertTrue(!Excel.isDateFormat("General"));
        assertEquals("1,234.50", Excel.formatNumber(1234.5, "#,##0.00"));
        assertEquals("0.3", Excel.formatNumber(0.1 + 0.2, "General"));
    }

    /** RK de ponto flutuante (só os 30 bits altos do double; serve para números redondos como os seriais). */
    private static int rkDouble(double v) {
        long bits = Double.doubleToLongBits(v);
        return (int) (bits >>> 32) & 0xFFFFFFFC;
    }
}
