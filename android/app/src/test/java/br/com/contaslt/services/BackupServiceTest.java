package br.com.contaslt.services;

import static br.com.contaslt.services.Testing.account;
import static br.com.contaslt.services.Testing.cents;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import br.com.contaslt.data.LocalStore;
import br.com.contaslt.data.schema.AccountRec;
import br.com.contaslt.data.schema.CategoryRec;
import br.com.contaslt.data.schema.CategoryRuleRec;
import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.data.schema.ItemRec;
import br.com.contaslt.data.schema.RecurringBillRec;
import br.com.contaslt.data.schema.TransactionRec;
import br.com.contaslt.data.schema.TransferRec;
import br.com.contaslt.domain.Ids;
import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.BackupRestored;
import br.com.contaslt.dto.Category;
import br.com.contaslt.dto.RecurringBill;
import br.com.contaslt.dto.TransactionInput;
import br.com.contaslt.dto.TransactionItemInput;

/**
 * Porte de frontend/src/services/backup.test.ts: exportar os dados da conta (zip com JSON sem ids + fotos) e importar
 * somando: nada duplica ao importar de novo, e noutro aparelho/conta tudo é recriado casando contas e categorias pelo
 * nome (porte de BackupTest).
 */
public class BackupServiceTest {

    private LocalStore origin;

    @Before
    public void setUp() {
        origin = Testing.freshStore();
        CatalogService.seedDefaults();
    }

    /** "Servidor" de fotos em memória. */
    static final class FakeServer implements BackupService.ReceiptIo {
        final Map<String, byte[]> photos = new HashMap<>();
        final String prefix;

        FakeServer(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public byte[] downloadReceipt(String file) {
            return photos.get(file);
        }

        @Override
        public String uploadReceipt(byte[] bytes, String originalName) {
            String name = prefix + "-" + Ids.newId() + ".jpg";
            photos.put(name, bytes);
            return name;
        }
    }

    private static AccountRec accountByName(String name) {
        for (AccountRec a : Ctx.db().list(Entity.ACCOUNT)) {
            if (a.name.equals(name)) {
                return a;
            }
        }
        return null;
    }

    private static List<TransactionRec> txs() {
        return Ctx.db().list(Entity.TRANSACTION);
    }

    private static TransactionRec find(String description, String type) {
        for (TransactionRec t : txs()) {
            if (t.description.startsWith(description) && (type == null || t.type.equals(type))) {
                return t;
            }
        }
        return null;
    }

    private static final class Zip {
        final List<String> names = new ArrayList<>();
        final Map<String, byte[]> files = new LinkedHashMap<>();
        JsonObject data;
    }

    private static Zip readZip(byte[] zip) throws IOException {
        Zip z = new Zip();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[4096];
            while ((e = in.getNextEntry()) != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                z.names.add(e.getName());
                z.files.put(e.getName(), bos.toByteArray());
            }
        }
        z.data = JsonParser.parseString(new String(z.files.get("contaslt.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        return z;
    }

    static byte[] zipOf(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static JsonObject findTx(JsonObject data, String description, String type) {
        for (JsonElement e : data.getAsJsonArray("transactions")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("description").getAsString().equals(description)
                && (type == null || o.get("type").getAsString().equals(type))) {
                return o;
            }
        }
        return null;
    }

    /** Transferência entre contas montada direto nos registros (as duas pernas + o vínculo). */
    private static void transfer(String fromId, String toId, long cents, String date) {
        String id = Ids.newId();
        TransactionRec out = TransactionsService.emptyTransaction();
        out.accountId = fromId;
        out.description = "Transferência";
        out.amount = cents;
        out.type = "EXPENSE";
        out.dueDate = date;
        out.paid = true;
        out.transferId = id;
        TransactionRec inn = TransactionsService.emptyTransaction();
        inn.accountId = toId;
        inn.description = "Transferência";
        inn.amount = cents;
        inn.type = "INCOME";
        inn.dueDate = date;
        inn.paid = true;
        inn.transferId = id;
        Ctx.db().put(Entity.TRANSACTION, out);
        Ctx.db().put(Entity.TRANSACTION, inn);
        TransferRec tr = new TransferRec();
        tr.id = id;
        tr.fromTransactionId = out.id;
        tr.toTransactionId = inn.id;
        tr.createdAt = out.createdAt;
        Ctx.db().put(Entity.TRANSFER, tr);
    }

    private static TransactionItemInput item(String description, Double quantity, Double unitPrice, double total) {
        TransactionItemInput i = new TransactionItemInput();
        i.description = description;
        i.quantity = quantity;
        i.unitPrice = unitPrice == null ? null : cents(unitPrice);
        i.total = cents(total);
        return i;
    }

    private static void assertItem(ItemRec i, String description, Double quantity, Long unitPrice, long total) {
        assertEquals(description, i.description);
        assertEquals(quantity, i.quantity);
        assertEquals(unitPrice, i.unitPrice);
        assertEquals(total, i.total);
    }

    @Test
    public void exportaSemIdsEImportaSomandoSemDuplicar() throws IOException {
        FakeServer server = new FakeServer("u1");
        Category parent = null;
        for (Category c : CatalogService.listCategories()) {
            if (c.type.equals("EXPENSE")) {
                parent = c;
                break;
            }
        }
        Category sub = CatalogService.createCategory(Testing.category("Padaria", "EXPENSE", "#112233", "croissant", parent.id));
        CatalogService.createRule("ifood", sub.id);
        AccountRec main = accountByName("Conta principal");
        Account savings = CatalogService.createAccount(account("Poupança", "SAVINGS", 10000, "#123456"));
        String receipt = "u1-" + Ids.newId() + ".jpg";
        server.photos.put(receipt, new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2, 3});
        TransactionInput in = Testing.tx("Mercado", 31.97, "EXPENSE", "2026-10-25", true, main.id, sub.id);
        in.merchant = "ALMEIDA";
        in.receiptFile = receipt;
        in.items = new ArrayList<>(Arrays.asList(item("LEITE", 2.0, 4.79, 9.58), item("PAO", null, null, 22.39)));
        TransactionsService.createTransaction(in);
        transfer(main.id, savings.id, 5000, "2026-10-26");

        byte[] zip = BackupService.exportBackup(server);
        Zip z = readZip(zip);
        assertEquals("contaslt.json", z.names.get(0));
        assertTrue(z.names.contains("receipts/" + receipt));
        JsonObject data = z.data;
        assertEquals("contaslt-data", data.get("format").getAsString());
        assertEquals(1, data.get("version").getAsInt());
        JsonObject mercado = findTx(data, "Mercado", null);
        assertFalse(mercado.has("id"));
        assertEquals("Conta principal", mercado.get("account").getAsString());
        assertEquals(parent.name + " › Padaria", mercado.get("category").getAsString());
        assertEquals("31.97", mercado.get("amount").toString()); // reais no arquivo, como o servidor
        JsonArray items = mercado.getAsJsonArray("items");
        assertEquals(2, items.size());
        assertEquals("{\"description\":\"LEITE\",\"quantity\":2,\"unitPrice\":4.79,\"total\":9.58}",
            items.get(0).toString());
        JsonObject out = findTx(data, "Transferência", "EXPENSE");
        int transferTo = out.get("transferTo").getAsInt();
        assertTrue(transferTo >= 0);
        assertEquals("INCOME", data.getAsJsonArray("transactions").get(transferTo).getAsJsonObject().get("type").getAsString());
        JsonObject rule = data.getAsJsonArray("categoryRules").get(0).getAsJsonObject();
        assertEquals("ifood", rule.get("keyword").getAsString());
        assertEquals(parent.name + " › Padaria", rule.get("category").getAsString());
        for (JsonElement e : data.getAsJsonArray("accounts")) {
            if (e.getAsJsonObject().get("name").getAsString().equals("Poupança")) {
                assertEquals("100", e.getAsJsonObject().get("initialBalance").toString());
            }
        }

        // Importar no mesmo aparelho: tudo já existe, nada entra.
        BackupRestored again = BackupService.importBackup(zip, server);
        assertEquals(0, again.transactions);
        assertEquals(3, again.skippedTransactions);
        assertEquals(0, again.accounts);
        assertEquals(0, again.categories);
        assertEquals(0, again.categoryRules);
        assertEquals(0, again.receipts);
        assertEquals(3, txs().size());
        assertEquals(2, Ctx.db().list(Entity.ACCOUNT).size());

        // Apagou um lançamento por engano? Importar de novo repõe só ele (com a foto, enviada de novo).
        TransactionsService.deleteTransaction(find("Mercado", null).id);
        BackupRestored repaired = BackupService.importBackup(zip, server);
        assertEquals(1, repaired.transactions);
        assertEquals(2, repaired.skippedTransactions);
        assertEquals(1, repaired.receipts);
        TransactionRec back = find("Mercado", null);
        assertEquals(2, back.items.size());
        assertItem(back.items.get(0), "LEITE", 2.0, 479L, 958);
        assertEquals(3197, back.amount);
        assertTrue(back.receiptFile.startsWith("u1-"));
        assertNotEquals(receipt, back.receiptFile);
        assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2, 3}, server.photos.get(back.receiptFile));

        // Noutra conta: contas e categorias casadas/criadas pelo nome, transferência pareada, foto no servidor novo.
        Testing.freshStore();
        CatalogService.seedDefaults();
        FakeServer other = new FakeServer("u2");
        BackupRestored moved = BackupService.importBackup(zip, other);
        assertEquals(3, moved.transactions);
        assertEquals(1, moved.accounts);
        assertEquals(1, moved.categories);
        assertEquals(1, moved.categoryRules);
        assertEquals(1, moved.transfers);
        assertEquals(1, moved.receipts);
        assertEquals(0, moved.skippedTransactions);
        TransactionRec movedMercado = find("Mercado", null);
        assertTrue(movedMercado.receiptFile.startsWith("u2-"));
        assertTrue(other.photos.containsKey(movedMercado.receiptFile));
        CategoryRec padaria = Ctx.db().get(Entity.CATEGORY, movedMercado.categoryId);
        assertEquals("Padaria", padaria.name);
        assertEquals(parent.name, Ctx.db().get(Entity.CATEGORY, padaria.parentId).name);
        CategoryRuleRec movedRule = Ctx.db().list(Entity.CATEGORY_RULE).get(0);
        assertEquals("ifood", movedRule.keyword);
        assertEquals(padaria.id, movedRule.categoryId);
        TransactionRec movedOut = find("Transferência", "EXPENSE");
        TransferRec tr = Ctx.db().get(Entity.TRANSFER, movedOut.transferId);
        assertEquals(movedOut.id, tr.fromTransactionId);
        TransactionRec peer = Ctx.db().get(Entity.TRANSACTION, tr.toTransactionId);
        assertEquals(tr.id, peer.transferId);
        assertEquals("Poupança", Ctx.db().get(Entity.ACCOUNT, peer.accountId).name);
        assertEquals(10000, accountByName("Poupança").initialBalance);
        assertEquals(0, BackupService.importBackup(zip, other).transactions); // de novo: nada duplica

        Ctx.useStoreForTests(origin);
        assertEquals(3, txs().size()); // e a conta de origem não mudou
    }

    @Test
    public void recorrenteESeusVencimentosVoltamLigadosSemOGeradorDuplicar() throws IOException {
        AccountRec main = accountByName("Conta principal");
        RecurringBill bill = RecurringService.createRecurring(Testing.bill("Aluguel", 1500, "EXPENSE", "MONTHLY",
            "2026-01-10", null, main.id, null));
        RecurringService.ensureOccurrences("2026-03");
        TransactionRec paid = null;
        for (TransactionRec t : txs()) {
            if ("2026-03-10".equals(t.scheduledDate)) {
                paid = t.copy();
            }
        }
        paid.paid = true;
        paid.amount = 155000;
        Ctx.db().put(Entity.TRANSACTION, paid);
        FakeServer server = new FakeServer("u1");
        byte[] zip = BackupService.exportBackup(server);
        JsonObject data = readZip(zip).data;
        JsonArray bills = data.getAsJsonArray("recurringBills");
        assertEquals(1, bills.size());
        JsonObject b0 = bills.get(0).getAsJsonObject();
        assertEquals("Aluguel", b0.get("description").getAsString());
        assertEquals("1500", b0.get("amount").toString());
        assertEquals("2026-01-10", b0.get("startDate").getAsString());
        assertEquals("Conta principal", b0.get("account").getAsString());
        JsonObject occ = null;
        for (JsonElement e : data.getAsJsonArray("transactions")) {
            JsonElement sd = e.getAsJsonObject().get("scheduledDate");
            if (sd != null && !sd.isJsonNull() && sd.getAsString().equals("2026-03-10")) {
                occ = e.getAsJsonObject();
            }
        }
        assertEquals(0, occ.get("recurringBill").getAsInt());

        // Mesmo aparelho: a regra é casada e a ocorrência é a mesma.
        BackupRestored same = BackupService.importBackup(zip, server);
        assertEquals(0, same.recurringBills);
        assertEquals(0, same.transactions);
        assertEquals(1, Ctx.db().list(Entity.RECURRING_BILL).size());
        assertEquals(bill.id, Ctx.db().list(Entity.RECURRING_BILL).get(0).id);

        Testing.freshStore();
        CatalogService.seedDefaults();
        BackupRestored restored = BackupService.importBackup(zip, new FakeServer("u2"));
        assertEquals(1, restored.recurringBills);
        RecurringBillRec newBill = Ctx.db().list(Entity.RECURRING_BILL).get(0);
        TransactionRec march = null;
        for (TransactionRec t : txs()) {
            if ("2026-03-10".equals(t.scheduledDate)) {
                march = t;
            }
        }
        assertEquals(newBill.id, march.recurringBillId);
        assertEquals(Ids.occurrenceId(newBill.id, "2026-03-10"), march.id);
        assertTrue(march.paid);
        assertEquals(155000, march.amount);
        int before = txs().size();
        RecurringService.ensureOccurrences("2026-03");
        assertEquals(before, txs().size());
    }

    @Test
    public void leOArquivoExportadoPeloServidorAntigo() throws IOException {
        String json = "{\"format\":\"contaslt-data\",\"version\":1,\"exportedAt\":\"2026-10-30T10:00:00\",\"app\":\"ContasLT\","
            + "\"accounts\":["
            + "{\"name\":\"conta principal \",\"type\":\"CHECKING\",\"initialBalance\":0.00,\"color\":\"#1B5E3F\",\"archived\":false,\"closingDay\":null,\"dueDay\":null},"
            + "{\"name\":\"Nubank\",\"type\":\"CREDIT_CARD\",\"initialBalance\":0,\"color\":\"#8A05BE\",\"archived\":false,\"closingDay\":3,\"dueDay\":10}],"
            + "\"categories\":[{\"name\":\"Lanches\",\"type\":\"EXPENSE\",\"color\":\"#112233\",\"icon\":\"tag\",\"archived\":false,\"parent\":\"Alimentação\"}],"
            + "\"categoryRules\":[{\"keyword\":\"padaria\",\"category\":\"Alimentação › Lanches\"},{\"keyword\":\"x\",\"category\":\"Não existe\"}],"
            + "\"recurringBills\":[],"
            + "\"transactions\":["
            + "{\"description\":\"Fatura\",\"amount\":\"1234.50\",\"type\":\"EXPENSE\",\"date\":\"2026-10-10\",\"paid\":true,\"account\":\"Conta principal\","
            + "\"category\":null,\"notes\":null,\"externalId\":\"FIT1\",\"merchant\":null,\"receipt\":\"u12-0b6c3c1e-1111-4222-8333-444455556666.jpg\","
            + "\"receiptAccessKey\":null,\"cardAccount\":\"Nubank\",\"invoiceMonth\":\"2026-10\",\"items\":[]},"
            + "{\"description\":\"Pão\",\"amount\":12.3,\"type\":\"EXPENSE\",\"date\":\"2026-10-11\",\"paid\":true,\"account\":\"Nubank\","
            + "\"category\":\"Alimentação › Lanches\",\"notes\":null,\"externalId\":\"FIT1\",\"merchant\":null,\"receipt\":null,"
            + "\"receiptAccessKey\":null,\"items\":[{\"description\":\"PAO\",\"quantity\":1.5,\"unitPrice\":\"8.20\",\"total\":\"12.30\"}]}]}";
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("contaslt.json", json.getBytes(StandardCharsets.UTF_8));
        files.put("receipts/u12-0b6c3c1e-1111-4222-8333-444455556666.jpg", new byte[] {9, 9});
        files.put("receipts/sobra.jpg", new byte[] {1});
        FakeServer server = new FakeServer("u3");
        BackupRestored r = BackupService.importBackup(zipOf(files), server);
        assertEquals(1, r.accounts);
        assertEquals(1, r.categories);
        assertEquals(1, r.categoryRules);
        assertEquals(2, r.transactions);
        assertEquals(1, r.receipts);
        assertEquals(1, server.photos.size());
        TransactionRec fatura = find("Fatura", null);
        assertEquals(123450, fatura.amount);
        assertEquals(accountByName("Conta principal").id, fatura.accountId); // casada pelo nome, sem caixa/espaços
        assertEquals(accountByName("Nubank").id, fatura.cardAccountId);
        assertEquals("2026-10-01", fatura.cardInvoiceMonth);
        assertTrue(fatura.receiptFile.startsWith("u3-"));
        TransactionRec pao = find("Pão", null);
        assertItem(pao.items.get(0), "PAO", 1.5, 820L, 1230);
        assertEquals("Lanches", Ctx.db().get(Entity.CATEGORY, pao.categoryId).name);
        assertEquals(Integer.valueOf(3), accountByName("Nubank").closingDay);
        assertEquals(Integer.valueOf(10), accountByName("Nubank").dueDay);
        assertNull(fatura.transferId);
    }

    private static void assertRejected(String message, byte[] zip) {
        Ctx.BusinessException e = assertThrows(Ctx.BusinessException.class,
            () -> BackupService.importBackup(zip, new FakeServer("u1")));
        if (message != null) {
            assertTrue(e.getMessage(), e.getMessage().contains(message));
        }
    }

    @Test
    public void recusaArquivosQueNaoSaoExportacoes() throws IOException {
        assertRejected("Arquivo inválido", "{\"format\":\"contaslt-data\"}".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> f = new LinkedHashMap<>();
        f.put("outro.json", "{}".getBytes(StandardCharsets.UTF_8));
        assertRejected("Arquivo inválido", zipOf(f));
        f.clear();
        f.put("contaslt.json", "{\"format\":\"outro\"}".getBytes(StandardCharsets.UTF_8));
        assertRejected("não é uma exportação", zipOf(f));
        f.put("contaslt.json", "{\"format\":\"contaslt-data\",\"version\":2}".getBytes(StandardCharsets.UTF_8));
        assertRejected("versão mais nova", zipOf(f));
        f.put("contaslt.json", "isto não é json {".getBytes(StandardCharsets.UTF_8));
        assertRejected("não é uma exportação", zipOf(f));
    }
}
