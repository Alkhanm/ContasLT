package br.com.contaslt.services;

import static br.com.contaslt.services.Testing.cents;
import static br.com.contaslt.services.TransfersServiceTest.assertThrowsMessage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import br.com.contaslt.data.ApiException;
import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.CardInvoice;
import br.com.contaslt.dto.ConfirmRow;
import br.com.contaslt.dto.ImportBatch;
import br.com.contaslt.dto.ImportConfirmRequest;
import br.com.contaslt.dto.ImportPreview;
import br.com.contaslt.dto.ImportResult;
import br.com.contaslt.dto.PreviewRow;
import br.com.contaslt.dto.RecurringBillInput;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.dto.TransactionFilter;
import br.com.contaslt.dto.TransactionInput;
import br.com.contaslt.dto.TransactionItemInput;
import br.com.contaslt.dto.TransactionPage;

/** Porte de frontend/src/services/imports.test.ts (ApiFlowTest, RecurringMatchTest e CardInvoiceTest). */
public class ImportsServiceTest {

    @Before
    public void setUp() {
        Testing.freshStore();
    }

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

    private static String row(String date, String amount, String memo, String fitid) {
        return "<STMTTRN>\n<TRNTYPE>" + (amount.startsWith("-") ? "DEBIT" : "CREDIT") + "\n<DTPOSTED>"
            + date.replace("-", "") + "\n<TRNAMT>" + amount + "\n<FITID>" + fitid + "\n<MEMO>" + memo + "\n</STMTTRN>\n";
    }

    private static byte[] ofx(String... rows) {
        String text = "OFXHEADER:100\nDATA:OFXSGML\nVERSION:102\nSECURITY:NONE\nENCODING:USASCII\n"
            + "CHARSET:1252\nCOMPRESSION:NONE\nOLDFILEUID:NONE\nNEWFILEUID:NONE\n\n<OFX>\n<BANKMSGSRSV1>\n<STMTTRNRS>\n<STMTRS>\n"
            + "<CURDEF>BRL\n<BANKTRANLIST>\n" + String.join("", rows)
            + "</BANKTRANLIST>\n</STMTRS>\n</STMTTRNRS>\n</BANKMSGSRSV1>\n</OFX>\n";
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static ImportPreview preview(String accountId, byte[] bytes) {
        return ImportsService.previewImport(accountId, "extrato.ofx", bytes);
    }

    private static PreviewRow rowNamed(ImportPreview pv, String fragment) {
        for (PreviewRow r : pv.rows) {
            if (r.description.contains(fragment)) {
                return r;
            }
        }
        throw new AssertionError("linha não encontrada: " + fragment);
    }

    private static ConfirmRow confirmRow(PreviewRow r, String categoryId) {
        ConfirmRow c = new ConfirmRow();
        c.date = r.date;
        c.description = r.description;
        c.amount = r.amount;
        c.type = r.type;
        c.externalId = r.externalId;
        c.categoryId = categoryId;
        c.paid = true;
        c.learnRule = false;
        return c;
    }

    private static ConfirmRow link(PreviewRow r, String linkTransactionId) {
        ConfirmRow c = confirmRow(r, null);
        c.linkTransactionId = linkTransactionId;
        return c;
    }

    private static ImportConfirmRequest request(String accountId, String filename, List<ConfirmRow> rows) {
        ImportConfirmRequest req = new ImportConfirmRequest();
        req.accountId = accountId;
        req.source = "OFX";
        req.filename = filename;
        req.rows = rows;
        return req;
    }

    private static int status(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e.status;
        }
        return 0;
    }

    private static TransactionPage list(String month, String accountId) {
        TransactionFilter f = new TransactionFilter();
        f.month = month;
        f.accountId = accountId;
        return TransactionsService.listTransactions(f);
    }

    private static TransactionItemInput item(String description, double quantity, double unitPrice, double total) {
        TransactionItemInput i = new TransactionItemInput();
        i.description = description;
        i.quantity = quantity;
        i.unitPrice = cents(unitPrice);
        i.total = cents(total);
        return i;
    }

    // ---------- ApiFlowTest ----------

    @Test
    public void previaEConfirmacaoDoOfxPulamDuplicadosEAprendemRegrasExcluirRemoveOsLancamentos() throws IOException {
        Testing.Catalog c = Testing.basicCatalog();
        ImportPreview pv = preview(c.account.id, sample());
        assertEquals("OFX", pv.source);
        assertEquals(3, pv.rows.size());
        assertEquals(0, pv.duplicates);
        List<String> dates = new ArrayList<>();
        for (PreviewRow r : pv.rows) {
            dates.add(r.date);
        }
        assertEquals(Arrays.asList("2026-10-07", "2026-10-05", "2026-10-03"), dates);

        List<ConfirmRow> rows = new ArrayList<>();
        for (PreviewRow r : pv.rows) {
            ConfirmRow cr = confirmRow(r, "INCOME".equals(r.type) ? c.income.id : c.expense.id);
            cr.learnRule = true;
            rows.add(cr);
        }
        ImportResult res = ImportsService.confirmImport(request(c.account.id, "extrato.ofx", rows));
        assertEquals(3, res.imported);

        // Segunda prévia do mesmo arquivo marca tudo como duplicado e sugere categorias aprendidas.
        ImportPreview again = preview(c.account.id, sample());
        assertEquals(3, again.duplicates);
        assertEquals(c.expense.id, again.rows.get(0).suggestedCategoryId);

        ImportResult reconfirm = ImportsService.confirmImport(request(c.account.id, "", rows));
        assertEquals(3, reconfirm.skipped);
        assertEquals(3, CatalogService.listRules().size());

        assertEquals(450000, DashboardService.buildDashboard("2026-10").income);

        // Histórico: o lote vazio da reconfirmação e o do primeiro envio.
        List<ImportBatch> history = ImportsService.importHistory();
        assertEquals(2, history.size());
        ImportBatch first = null;
        for (ImportBatch b : history) {
            if (b.id.equals(res.batchId)) {
                first = b;
            }
        }
        assertEquals("OFX", first.source);
        assertEquals("extrato.ofx", first.filename);
        assertEquals("Conta", first.accountName);
        assertEquals(3, first.importedCount);

        int before = list("2026-10", null).items.size();
        assertEquals(3, ImportsService.deleteImport(res.batchId));
        assertEquals(before - 3, list("2026-10", null).items.size());
        assertEquals(404, status(() -> ImportsService.deleteImport(res.batchId)));
    }

    @Test
    public void lancamentoDeComprovanteComItensEVinculadoPelaImportacao() throws IOException {
        Testing.Catalog c = Testing.basicCatalog();
        TransactionInput in = Testing.tx("Padaria Pão Quente", 52.3, "EXPENSE", "2026-10-06", true, c.account.id, c.expense.id);
        in.merchant = "Padaria Pão Quente";
        in.items = new ArrayList<>(Arrays.asList(item("Pão francês", 10, 0.8, 8), item("Bolo de cenoura", 1, 44.3, 44.3)));
        Transaction tx = TransactionsService.createTransaction(in).transaction;

        // A prévia reconhece a linha de 52,30 (07/10) como a mesma compra.
        ImportPreview pv = preview(c.account.id, sample());
        PreviewRow matched = null;
        for (PreviewRow r : pv.rows) {
            if (r.matchTransactionId != null) {
                matched = r;
                break;
            }
        }
        assertNotNull(matched);
        assertEquals(5230, matched.amount);
        assertEquals(tx.id, matched.matchTransactionId);
        assertEquals(2, matched.matchItemCount);
        assertEquals(c.expense.id, matched.matchCategoryId);
        assertEquals("Conta", matched.matchAccountName);
        assertFalse(matched.duplicate);

        List<ConfirmRow> rows = new ArrayList<>();
        for (PreviewRow r : pv.rows) {
            rows.add(r.matchTransactionId != null ? link(r, r.matchTransactionId)
                : confirmRow(r, "INCOME".equals(r.type) ? c.income.id : c.expense.id));
        }
        ImportResult res = ImportsService.confirmImport(request(c.account.id, "extrato.ofx", rows));
        assertEquals(1, res.linked);
        assertEquals(2, res.imported);

        Transaction after = TransactionsService.getTransaction(tx.id);
        assertEquals(matched.externalId, after.externalId);
        assertEquals(res.batchId, after.importBatchId);
        assertEquals(2, after.items.size());
        assertEquals("Padaria Pão Quente", after.description);
        assertEquals(3, ImportsService.importHistory().get(0).importedCount);

        // Importar o mesmo extrato de novo: a linha agora é duplicada (FITID já usado), não sugere vínculo.
        ImportPreview again = preview(c.account.id, sample());
        assertEquals(3, again.duplicates);
        for (PreviewRow r : again.rows) {
            assertNull(r.matchTransactionId);
        }

        // Vincular de novo é recusado.
        ConfirmRow relink = link(matched, tx.id);
        relink.externalId = "outro";
        assertThrowsMessage("O lançamento \"Padaria Pão Quente\" já está vinculado a uma importação",
            () -> ImportsService.confirmImport(request(c.account.id, "x.ofx", new ArrayList<>(Arrays.asList(relink)))));

        // Excluir a importação apaga só os 2 criados por ela; o lançamento vinculado fica, sem o vínculo.
        assertEquals(2, ImportsService.deleteImport(res.batchId));
        Transaction kept = TransactionsService.getTransaction(tx.id);
        assertNull(kept.importBatchId);
        assertNull(kept.externalId);
        assertEquals(2, kept.items.size());
    }

    @Test
    public void confirmacaoETudoOuNadaEValidaARequisicao() throws IOException {
        Testing.Catalog c = Testing.basicCatalog();
        ImportPreview pv = preview(c.account.id, sample());
        // A linha de despesa com categoria de receita derruba a confirmação inteira: nada é gravado.
        List<ConfirmRow> rows = new ArrayList<>();
        for (PreviewRow r : pv.rows) {
            rows.add(confirmRow(r, c.income.id));
        }
        assertThrowsMessage("Categoria \"Salário\" não é do tipo do lançamento \"IFOOD *RESTAURANTE\"",
            () -> ImportsService.confirmImport(request(c.account.id, "extrato.ofx", rows)));
        assertEquals(0, ImportsService.importHistory().size());
        assertEquals(0, list("2026-10", null).items.size());

        ApiException e = assertThrows(ApiException.class,
            () -> ImportsService.confirmImport(request(c.account.id, "x", new ArrayList<>())));
        assertEquals(400, e.status);
        assertEquals("Selecione ao menos um lançamento", e.errors.get("rows"));
        assertEquals(404, status(() -> ImportsService.confirmImport(request(c.account.id, "x",
            new ArrayList<>(Arrays.asList(link(pv.rows.get(0), "nao-existe")))))));
    }

    @Test
    public void formatoDoArquivoPelaExtensaoOuPeloConteudo() throws IOException {
        assertEquals("OFX", ImportsService.detectSource("a.OFX", new byte[0]));
        assertEquals("EXCEL", ImportsService.detectSource("a.xls", new byte[0]));
        assertEquals("OFX", ImportsService.detectSource("arquivo", sample()));
        assertEquals("EXCEL", ImportsService.detectSource("arquivo", new byte[] {0x50, 0x4b, 3, 4, 0}));
        assertThrowsMessage("Formato não suportado. Envie um arquivo .ofx, .xlsx ou .xls",
            () -> ImportsService.detectSource("a.csv", "x;y".getBytes(StandardCharsets.UTF_8)));
        Testing.Catalog c = Testing.basicCatalog();
        byte[] oi = "oi".getBytes(StandardCharsets.UTF_8);
        assertThrowsMessage("O arquivo não parece ser um extrato OFX válido",
            () -> ImportsService.previewImport(c.account.id, "a.ofx", oi));
        assertEquals(422, status(() -> ImportsService.previewImport(c.account.id, "a.ofx", oi)));
        // Planilha ilegível também vira 422 com a mensagem do leitor.
        ApiException e = assertThrows(ApiException.class,
            () -> ImportsService.previewImport(c.account.id, "a.xlsx", oi));
        assertEquals(422, e.status);
        assertTrue(e.getMessage().startsWith("Não foi possível ler a planilha: "));
    }

    // ---------- RecurringMatchTest ----------

    private static String bill(String accountId, String description, double amount, String startDate,
                               Integer matchStrictness, String fromTransactionId) {
        RecurringBillInput b = Testing.bill(description, amount, "EXPENSE", "MONTHLY", startDate, null, accountId, null);
        b.matchStrictness = matchStrictness;
        b.fromTransactionId = fromTransactionId;
        return RecurringService.createRecurring(b).id;
    }

    @Test
    public void linhaDoExtratoQuitaOVencimentoPendenteComValorEDataReais() {
        Testing.Catalog c = Testing.basicCatalog();
        String billId = bill(c.account.id, "Internet LINQ", 99.99, "2035-03-01", null, null);

        // Boleto pago no dia 10 com multa: valor e data diferentes, mas o nome bate. O mercado não bate com nada.
        ImportPreview pv = preview(c.account.id, ofx(
            row("2035-03-10", "-102.00", "Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA", "A1"),
            row("2035-03-10", "-102.00", "SUPERMERCADO ALMEIDA", "A2")));
        PreviewRow linq = rowNamed(pv, "LINQ");
        PreviewRow market = rowNamed(pv, "SUPERMERCADO");
        assertNotNull(linq.matchTransactionId);
        assertTrue(linq.matchRecurring);
        assertEquals("Internet LINQ", linq.matchDescription);
        assertEquals("2035-03-01", linq.matchDate);
        assertEquals(Long.valueOf(9999), linq.matchAmount);
        assertNull(market.matchTransactionId);
        String occurrenceId = linq.matchTransactionId;

        ImportResult result = ImportsService.confirmImport(request(c.account.id, "extrato.ofx",
            new ArrayList<>(Arrays.asList(link(linq, occurrenceId), confirmRow(market, null)))));
        assertEquals(1, result.linked);
        assertEquals(1, result.imported);

        // O vencimento virou o pagamento real: pago, com valor e data do extrato, e a data prevista guardada.
        Transaction occ = TransactionsService.getTransaction(occurrenceId);
        assertTrue(occ.paid);
        assertEquals(10200, occ.amount);
        assertEquals("2035-03-10", occ.dueDate);
        assertEquals("2035-03-01", occ.scheduledDate);
        assertEquals(Long.valueOf(9999), occ.expectedAmount);
        assertEquals(billId, occ.recurringBillId);
        assertEquals("Internet LINQ", occ.description);

        // Março tem só o vencimento pago e o mercado: nada duplicado, e o gerador não recria o dia 1º.
        assertEquals(2, list("2035-03", null).items.size());
        // Abril segue previsto com o valor estimado da regra.
        List<Transaction> april = list("2035-04", null).items;
        assertEquals(1, april.size());
        assertEquals(9999, april.get(0).amount);
    }

    @Test
    public void aPrecisaoControlaQuantoDataEValorPodemVariar() {
        Testing.Catalog c = Testing.basicCatalog();
        bill(c.account.id, "Energia Enel", 200, "2035-05-05", 10, null);
        bill(c.account.id, "Água Saneago", 80, "2035-05-05", 1, null);

        ImportPreview pv = preview(c.account.id, ofx(
            row("2035-05-12", "-204.00", "PAGTO ENEL DISTRIBUICAO", "B1"),
            row("2035-05-28", "-115.00", "SANEAGO SANEAMENTO DE GOIAS", "B2")));
        // Nível 10: só casaria com valor e data exatos.
        assertNull(rowNamed(pv, "ENEL").matchTransactionId);
        // Nível 1: praticamente só o nome, dentro do mês.
        assertNotNull(rowNamed(pv, "SANEAGO").matchTransactionId);
        assertEquals("Água Saneago", rowNamed(pv, "SANEAGO").matchDescription);

        ImportPreview exact = preview(c.account.id, ofx(row("2035-05-05", "-200.00", "PAGTO ENEL DISTRIBUICAO", "B3")));
        assertEquals("Energia Enel", exact.rows.get(0).matchDescription);
    }

    @Test
    public void pagamentosAnterioresEnsinamComoAContaApareceNoExtrato() {
        Testing.Catalog c = Testing.basicCatalog();
        // Regra com nome "Internet" não compartilha palavra com o extrato; o pagamento anterior adotado sim.
        Transaction tx = TransactionsService.createTransaction(Testing.tx(
            "Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA", 99.99, "EXPENSE", "2035-07-20", true,
            c.account.id, null)).transaction;
        bill(c.account.id, "Internet", 99.99, "2035-07-20", null, tx.id);

        ImportPreview pv = preview(c.account.id, ofx(row("2035-08-22", "-99.99",
            "Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA", "C1")));
        assertEquals("Internet", pv.rows.get(0).matchDescription);
        assertEquals("2035-08-20", pv.rows.get(0).matchDate);
    }

    // ---------- CardInvoiceTest ----------

    @Test
    public void linhaDoExtratoQuePagaAFaturaViraDespesaLigadaAoCartao() {
        Testing.Catalog c = Testing.basicCatalog();
        String checking = c.account.id;
        // Fecha dia 25, vence dia 5 do mês seguinte.
        Account card = CatalogService.createAccount(Testing.card("Nubank", 0, "#820AD1", 25, 5));
        TransactionsService.createTransaction(Testing.tx("Mercado", 300, "EXPENSE", "2034-09-10", true, card.id, null));

        ImportPreview pv = preview(checking, ofx(row("2034-10-05", "-480.00", "PAGAMENTO FATURA NUBANK", "F1"),
            row("2034-10-06", "-35.90", "PADARIA", "F2")));
        PreviewRow line = rowNamed(pv, "FATURA");
        assertEquals(card.id, line.invoiceCardId);
        assertEquals("Nubank", line.invoiceCardName);
        assertEquals("2034-10", line.invoiceMonth);
        assertEquals("2034-10-05", line.invoiceDueDate);
        assertEquals(Long.valueOf(30000), line.invoiceTotal);
        assertEquals(Long.valueOf(0), line.invoicePaid);
        assertNull(rowNamed(pv, "PADARIA").invoiceCardId);

        ConfirmRow fatura = confirmRow(line, null);
        fatura.amount = 48000L;
        fatura.invoiceCardId = card.id;
        fatura.invoiceMonth = "2034-10";
        ConfirmRow padaria = confirmRow(rowNamed(pv, "PADARIA"), null);
        ImportResult res = ImportsService.confirmImport(request(checking, "extrato.ofx",
            new ArrayList<>(Arrays.asList(fatura, padaria))));
        assertEquals(1, res.invoicePayments);

        // A fatura é despesa da conta corrente (480 + 35,90) e agrega a compra do cartão.
        assertEquals(51590, list("2034-10", checking).summary.expense);
        CardInvoice inv = CardsService.summarizeInvoice(CardsService.requireCard(card.id), "2034-10");
        assertEquals(48000, inv.paid);
        assertEquals(18000, inv.undetailed);
        assertEquals("PAID", inv.status);
        for (Account a : CatalogService.listAccounts()) {
            if (a.id.equals(card.id)) {
                assertEquals(0, a.balance);
            }
        }

        // Pagamento de fatura a partir do próprio cartão é recusado.
        ConfirmRow fromCard = new ConfirmRow();
        fromCard.date = "2034-10-05";
        fromCard.description = "PAGAMENTO";
        fromCard.amount = 100L;
        fromCard.type = "EXPENSE";
        fromCard.paid = true;
        fromCard.invoiceCardId = card.id;
        assertThrowsMessage("Pagamento de fatura é uma saída da conta: \"PAGAMENTO\"",
            () -> ImportsService.confirmImport(request(card.id, "x", new ArrayList<>(Arrays.asList(fromCard)))));

        // OFX do próprio cartão: "Pagamento recebido" é sinalizado para não virar receita nem estorno.
        ImportPreview cardPv = ImportsService.previewImport(card.id, "cartao.ofx",
            ofx(row("2034-10-05", "480.00", "Pagamento recebido", "C1"), row("2034-09-12", "-45.00", "IFOOD", "C2")));
        assertTrue(rowNamed(cardPv, "Pagamento recebido").invoicePaymentReceived);
        assertFalse(rowNamed(cardPv, "IFOOD").invoicePaymentReceived);
    }

    // ---------- Planilha ----------

    @Test
    public void planilhaXlsxPassaPelaMesmaPrevia() {
        Testing.Catalog c = Testing.basicCatalog();
        java.util.Map<String, String> files = new java.util.LinkedHashMap<>();
        files.put("xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
            + "<sheets><sheet name=\"A\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        files.put("xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
            + "</Relationships>");
        files.put("xl/worksheets/sheet1.xml", "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
            + "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Data</t></is></c><c r=\"B1\" t=\"inlineStr\"><is><t>Descrição</t></is></c>"
            + "<c r=\"C1\" t=\"inlineStr\"><is><t>Valor</t></is></c><c r=\"D1\" t=\"inlineStr\"><is><t>Categoria</t></is></c></row>"
            + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>10/02/2026</t></is></c><c r=\"B2\" t=\"inlineStr\"><is><t>Feira</t></is></c>"
            + "<c r=\"C2\"><v>-42.1</v></c><c r=\"D2\" t=\"inlineStr\"><is><t>mercado</t></is></c></row>"
            + "</sheetData></worksheet>");
        ImportPreview pv = ImportsService.previewImport(c.account.id, "extrato.xlsx", zip(files));
        assertEquals("EXCEL", pv.source);
        assertEquals(1, pv.rows.size());
        assertEquals(4210, pv.rows.get(0).amount);
        assertEquals("EXPENSE", pv.rows.get(0).type);
        assertEquals(c.expense.id, pv.rows.get(0).suggestedCategoryId);
    }

    private static byte[] zip(java.util.Map<String, String> files) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(bos)) {
                for (java.util.Map.Entry<String, String> e : files.entrySet()) {
                    z.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                    z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    z.closeEntry();
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
