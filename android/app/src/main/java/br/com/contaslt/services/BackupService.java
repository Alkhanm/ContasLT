package br.com.contaslt.services;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import br.com.contaslt.data.LocalStore;
import br.com.contaslt.data.schema.AccountRec;
import br.com.contaslt.data.schema.CategoryRec;
import br.com.contaslt.data.schema.CategoryRuleRec;
import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.data.schema.ItemRec;
import br.com.contaslt.data.schema.RecurringBillRec;
import br.com.contaslt.data.schema.TransactionRec;
import br.com.contaslt.data.schema.TransferRec;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.domain.Ids;
import br.com.contaslt.domain.Money;
import br.com.contaslt.domain.Text;
import br.com.contaslt.dto.BackupRestored;

/**
 * Exportação e importação dos dados da conta (porte de frontend/src/services/backup.ts): contas, categorias, regras,
 * recorrentes e lançamentos (com itens, fotos e transferências). Não é um dump: o arquivo não carrega ids, as
 * referências são por nome (conta, categoria "Pai › Filha") e por posição dentro do próprio arquivo (par de uma
 * transferência, regra recorrente). Valores em reais no JSON, como o servidor exportava.
 *
 * <p>A importação SOMA ao que já existe: contas e categorias são casadas pelo nome e criadas se faltarem; lançamento
 * igual a um já existente (mesma conta, data, tipo, valor e descrição, ou mesmo identificador de extrato, ou a mesma
 * ocorrência de recorrente) é pulado. Importar o mesmo arquivo duas vezes não duplica nada.
 *
 * <p>As fotos dos comprovantes ficam no servidor: quem chama injeta o download (exportação) e o upload (importação).
 */
public final class BackupService {

    public static final String FORMAT = "contaslt-data";
    public static final int VERSION = 1;
    private static final String JSON_ENTRY = "contaslt.json";
    private static final String RECEIPTS_DIR = "receipts/";
    private static final String SEP = " › ";
    private static final long MAX_UNZIPPED = 1024L * 1024 * 1024;
    private static final Logger LOG = Logger.getLogger("contaslt");
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping()
        .create();

    private static final List<String> TYPES = Arrays.asList("EXPENSE", "INCOME");
    private static final List<String> ACCOUNT_TYPES = Arrays.asList(
        "CHECKING", "SAVINGS", "CREDIT_CARD", "CASH", "INVESTMENT", "OTHER");
    private static final List<String> FREQUENCIES = Arrays.asList(
        "WEEKLY", "BIWEEKLY", "MONTHLY", "BIMONTHLY", "QUARTERLY", "SEMIANNUAL", "YEARLY");

    private BackupService() {
    }

    /** Acesso às fotos dos comprovantes (no servidor). */
    public interface ReceiptIo {
        /** Bytes da foto guardada no servidor, ou null se ela sumiu. */
        byte[] downloadReceipt(String file);

        /** Sobe a foto e devolve o nome com que o servidor a guardou. */
        String uploadReceipt(byte[] bytes, String originalName);
    }

    // ---------- Exportação ----------

    private static int cmp(String a, String b) {
        return Integer.signum(a.compareTo(b));
    }

    private static int byCreation(br.com.contaslt.data.schema.Rec a, br.com.contaslt.data.schema.Rec b) {
        int c = cmp(a.createdAt, b.createdAt);
        return c != 0 ? c : cmp(a.id, b.id);
    }

    /** Zip com o JSON primeiro (o servidor antigo lia nessa ordem) e as fotos dos comprovantes depois. */
    public static byte[] exportBackup(ReceiptIo io) {
        JsonObject root = snapshot();
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry(JSON_ENTRY));
                zip.write(PRETTY.toJson(root).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                Set<String> done = new HashSet<>();
                for (JsonElement e : root.getAsJsonArray("transactions")) {
                    JsonElement r = e.getAsJsonObject().get("receipt");
                    String file = r == null || r.isJsonNull() ? null : r.getAsString();
                    if (file == null || file.isEmpty() || !done.add(file)) {
                        continue;
                    }
                    byte[] bytes = io.downloadReceipt(file);
                    if (bytes != null) {
                        zip.putNextEntry(new ZipEntry(RECEIPTS_DIR + file));
                        zip.write(bytes);
                        zip.closeEntry();
                    }
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reais como o JSON.stringify do PWA: inteiro sem casas ("100"), senão o decimal ("12.5"). */
    private static JsonElement reais(Long cents) {
        if (cents == null) {
            return JsonNull.INSTANCE;
        }
        if (cents % 100 == 0) {
            return new JsonPrimitive(cents / 100);
        }
        return new JsonPrimitive(BigDecimal.valueOf(cents, 2).stripTrailingZeros());
    }

    private static JsonElement str(String s) {
        return s == null ? JsonNull.INSTANCE : new JsonPrimitive(s);
    }

    /** Quantidade como o JSON.stringify: inteira sem ".0". */
    private static JsonElement quantity(Double q) {
        if (q == null || q.isNaN() || q.isInfinite()) {
            return JsonNull.INSTANCE;
        }
        if (q == Math.rint(q) && Math.abs(q) < 1e15) {
            return new JsonPrimitive(q.longValue());
        }
        return new JsonPrimitive(q);
    }

    private static JsonElement num(Number n) {
        return n == null ? JsonNull.INSTANCE : new JsonPrimitive(n);
    }

    /** O JSON do arquivo (sem ids), montado a partir dos dados do aparelho. */
    public static JsonObject snapshot() {
        LocalStore s = Ctx.db();

        Map<String, String> accountNames = new HashMap<>();
        List<AccountRec> accountList = new ArrayList<>(s.list(Entity.ACCOUNT));
        accountList.sort(BackupService::byCreation);
        JsonArray accounts = new JsonArray();
        for (AccountRec a : accountList) {
            accountNames.put(a.id, a.name);
            JsonObject o = new JsonObject();
            o.add("name", str(a.name));
            o.add("type", str(a.type));
            o.add("initialBalance", reais(a.initialBalance));
            o.add("color", str(a.color));
            o.addProperty("archived", a.archived);
            o.add("closingDay", num(a.closingDay));
            o.add("dueDay", num(a.dueDay));
            accounts.add(o);
        }

        // Pais antes das filhas.
        List<CategoryRec> cats = new ArrayList<>(s.list(Entity.CATEGORY));
        cats.sort((a, b) -> {
            int c = Integer.compare(a.parentId != null ? 1 : 0, b.parentId != null ? 1 : 0);
            return c != 0 ? c : byCreation(a, b);
        });
        Map<String, String> categoryNames = new HashMap<>();
        for (CategoryRec c : cats) {
            categoryNames.put(c.id, c.name);
        }
        Map<String, String> categoryPaths = new HashMap<>();
        JsonArray categories = new JsonArray();
        for (CategoryRec c : cats) {
            String parent = c.parentId == null ? null : categoryNames.get(c.parentId);
            categoryPaths.put(c.id, parent == null ? c.name : parent + SEP + c.name);
            JsonObject o = new JsonObject();
            o.add("name", str(c.name));
            o.add("type", str(c.type));
            o.add("color", str(c.color));
            o.add("icon", str(c.icon));
            o.addProperty("archived", c.archived);
            o.add("parent", str(parent));
            categories.add(o);
        }

        List<CategoryRuleRec> ruleList = new ArrayList<>(s.list(Entity.CATEGORY_RULE));
        ruleList.sort(BackupService::byCreation);
        JsonArray categoryRules = new JsonArray();
        for (CategoryRuleRec r : ruleList) {
            JsonObject o = new JsonObject();
            o.add("keyword", str(r.keyword));
            o.add("category", str(r.categoryId == null ? null : categoryPaths.get(r.categoryId)));
            categoryRules.add(o);
        }

        Map<String, Integer> billRefs = new HashMap<>();
        List<RecurringBillRec> billList = new ArrayList<>(s.list(Entity.RECURRING_BILL));
        billList.sort(BackupService::byCreation);
        JsonArray recurringBills = new JsonArray();
        for (int i = 0; i < billList.size(); i++) {
            RecurringBillRec b = billList.get(i);
            billRefs.put(b.id, i);
            JsonObject o = new JsonObject();
            o.add("description", str(b.description));
            o.add("amount", reais(b.amount));
            o.add("type", str(b.type));
            o.add("frequency", str(b.frequency));
            o.add("startDate", str(b.startDate));
            o.add("endDate", str(b.endDate));
            o.add("occurrences", num(b.occurrences));
            o.add("notes", str(b.notes));
            o.addProperty("active", b.active);
            o.addProperty("startNumber", b.startNumber);
            o.addProperty("matchStrictness", b.matchStrictness);
            o.add("account", str(b.accountId == null ? null : accountNames.get(b.accountId)));
            o.add("category", str(b.categoryId == null ? null : categoryPaths.get(b.categoryId)));
            recurringBills.add(o);
        }

        // Transferências: a perna de saída aponta para a de entrada pela posição no arquivo.
        Map<String, String> transferPeer = new HashMap<>();
        for (TransferRec tr : s.list(Entity.TRANSFER)) {
            transferPeer.put(tr.fromTransactionId, tr.toTransactionId);
        }
        List<TransactionRec> txs = new ArrayList<>(s.list(Entity.TRANSACTION));
        txs.sort((a, b) -> {
            int c = cmp(a.dueDate, b.dueDate);
            return c != 0 ? c : byCreation(a, b);
        });
        Map<String, Integer> txRefs = new HashMap<>();
        for (int i = 0; i < txs.size(); i++) {
            txRefs.put(txs.get(i).id, i);
        }
        JsonArray transactions = new JsonArray();
        for (TransactionRec t : txs) {
            JsonObject o = new JsonObject();
            o.add("description", str(t.description));
            o.add("amount", reais(t.amount));
            o.add("type", str(t.type));
            o.add("date", str(t.dueDate));
            o.addProperty("paid", t.paid);
            String accountName = accountNames.get(t.accountId);
            o.add("account", str(accountName != null ? accountName : ""));
            o.add("category", str(t.categoryId == null ? null : categoryPaths.get(t.categoryId)));
            o.add("notes", str(t.notes));
            o.add("externalId", str(t.externalId));
            o.add("merchant", str(t.merchant));
            o.add("receipt", str(t.receiptFile));
            o.add("receiptAccessKey", str(t.receiptAccessKey));
            o.add("items", new JsonArray());
            if (t.recurringBillId != null) {
                Integer ref = billRefs.get(t.recurringBillId);
                if (ref != null) {
                    o.addProperty("recurringBill", ref);
                }
                o.add("installmentNumber", num(t.installmentNumber));
                o.add("scheduledDate", str(t.scheduledDate));
            }
            if (t.cardAccountId != null) {
                o.add("cardAccount", str(accountNames.get(t.cardAccountId)));
                o.add("invoiceMonth", str(t.cardInvoiceMonth == null ? null
                    : t.cardInvoiceMonth.substring(0, Math.min(7, t.cardInvoiceMonth.length()))));
            }
            String peer = transferPeer.get(t.id);
            Integer peerRef = peer == null ? null : txRefs.get(peer);
            if (peerRef != null) {
                o.addProperty("transferTo", peerRef);
            }
            JsonArray items = new JsonArray();
            if (t.items != null) {
                for (ItemRec i : t.items) {
                    JsonObject it = new JsonObject();
                    it.add("description", str(i.description));
                    it.add("quantity", quantity(i.quantity));
                    it.add("unitPrice", reais(i.unitPrice));
                    it.add("total", reais(i.total));
                    items.add(it);
                }
            }
            o.add("items", items);
            transactions.add(o);
        }

        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("version", VERSION);
        root.addProperty("exportedAt", Dates.nowIso());
        root.addProperty("app", "ContasLT");
        root.add("accounts", accounts);
        root.add("categories", categories);
        root.add("categoryRules", categoryRules);
        root.add("recurringBills", recurringBills);
        root.add("transactions", transactions);
        return root;
    }

    // ---------- Importação (soma ao que existe) ----------

    /** Plano da importação: tudo o que será gravado, calculado antes de subir as fotos. */
    private static final class Plan {
        final BackupRestored counts = new BackupRestored();
        final List<AccountRec> accounts = new ArrayList<>();
        final List<CategoryRec> categories = new ArrayList<>();
        final List<CategoryRuleRec> rules = new ArrayList<>();
        final List<RecurringBillRec> bills = new ArrayList<>();
        final List<TransactionRec> transactions = new ArrayList<>();
        final List<TransferRec> transfers = new ArrayList<>();
        /** Lançamento novo → nome da foto no arquivo. */
        final Map<TransactionRec, String> receiptOf = new IdentityHashMap<>();
        final List<TransactionRec> receiptOrder = new ArrayList<>();
    }

    public static BackupRestored importBackup(byte[] zip, ReceiptIo io) {
        Map<String, byte[]> files;
        try {
            files = unzip(zip);
        } catch (IOException | RuntimeException e) {
            throw invalidFile();
        }
        byte[] entry = files.get(JSON_ENTRY);
        if (entry == null) {
            throw invalidFile();
        }
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(new String(entry, StandardCharsets.UTF_8));
            root = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            throw new Ctx.BusinessException("Este arquivo não é uma exportação do ContasLT.");
        }
        JsonElement format = root == null ? null : root.get("format");
        if (format == null || !format.isJsonPrimitive() || !format.getAsJsonPrimitive().isString()
            || !FORMAT.equals(format.getAsString())) {
            throw new Ctx.BusinessException("Este arquivo não é uma exportação do ContasLT.");
        }
        if (intOf(root.get("version"), 0) > VERSION) {
            throw new Ctx.BusinessException("Arquivo de uma versão mais nova do app. Atualize antes de importar.");
        }

        Plan plan = merge(root);

        // Fotos: só as dos lançamentos que entram; cada uma sobe uma vez e ganha o nome que o servidor der.
        Map<String, String> uploaded = new HashMap<>();
        Set<String> names = new LinkedHashSet<>();
        for (TransactionRec t : plan.receiptOrder) {
            names.add(plan.receiptOf.get(t));
        }
        for (String name : names) {
            byte[] bytes = files.get(RECEIPTS_DIR + name);
            if (bytes == null) {
                uploaded.put(name, null);
                continue;
            }
            try {
                uploaded.put(name, io.uploadReceipt(bytes, name));
                plan.counts.receipts++;
            } catch (RuntimeException err) {
                LOG.log(Level.WARNING, "[contaslt] importação: não consegui enviar a foto " + name, err);
                uploaded.put(name, null);
            }
        }
        for (TransactionRec t : plan.receiptOrder) {
            t.receiptFile = uploaded.get(plan.receiptOf.get(t));
        }

        LocalStore s = Ctx.db();
        for (AccountRec a : plan.accounts) {
            s.put(Entity.ACCOUNT, a);
        }
        for (CategoryRec c : plan.categories) {
            s.put(Entity.CATEGORY, c);
        }
        for (CategoryRuleRec r : plan.rules) {
            s.put(Entity.CATEGORY_RULE, r);
        }
        for (RecurringBillRec b : plan.bills) {
            s.put(Entity.RECURRING_BILL, b);
        }
        for (TransactionRec t : plan.transactions) {
            s.put(Entity.TRANSACTION, t);
        }
        for (TransferRec tr : plan.transfers) {
            s.put(Entity.TRANSFER, tr);
        }
        RecurringService.invalidateEnsured();
        return plan.counts;
    }

    private static Ctx.BusinessException invalidFile() {
        return new Ctx.BusinessException(
            "Arquivo inválido: esperado o zip exportado pelo ContasLT (com " + JSON_ENTRY + ").");
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        long total = 0;
        boolean any = false;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zin.getNextEntry()) != null) {
                any = true;
                if (e.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_UNZIPPED) {
                        throw new IOException("arquivo grande demais");
                    }
                    bos.write(buf, 0, n);
                }
                out.put(e.getName(), bos.toByteArray());
            }
        }
        if (!any) {
            throw new IOException("zip vazio ou inválido");
        }
        return out;
    }

    private static Plan merge(JsonObject root) {
        LocalStore s = Ctx.db();
        String createdAt = Dates.nowIso();
        Plan plan = new Plan();
        BackupRestored counts = plan.counts;

        // Contas pelo nome.
        Map<String, String> accounts = new HashMap<>();
        for (AccountRec a : s.list(Entity.ACCOUNT)) {
            accounts.put(key(a.name), a.id);
        }
        for (JsonObject n : array(root.get("accounts"))) {
            String name = Text.trim(text(n, "name"));
            if (accounts.containsKey(key(name))) {
                continue;
            }
            AccountRec a = new AccountRec();
            a.id = Ids.newId();
            a.name = name;
            a.type = oneOf(n.get("type"), ACCOUNT_TYPES, "CHECKING");
            a.initialBalance = cents(n, "initialBalance", 0);
            a.color = text(n, "color", "#1B5E3F");
            a.archived = bool(n, "archived");
            a.closingDay = intOrNull(n.get("closingDay"));
            a.dueDay = intOrNull(n.get("dueDay"));
            a.createdAt = createdAt;
            accounts.put(key(name), a.id);
            plan.accounts.add(a);
        }
        counts.accounts = plan.accounts.size();

        // Categorias por tipo + "Pai › Filha" (pais antes das filhas).
        Map<String, String> categories = new HashMap<>();
        List<CategoryRec> existing = s.list(Entity.CATEGORY);
        Map<String, String> existingNames = new HashMap<>();
        for (CategoryRec c : existing) {
            existingNames.put(c.id, c.name);
        }
        for (CategoryRec c : existing) {
            String parent = c.parentId == null ? null : existingNames.get(c.parentId);
            categories.put(categoryKey(c.type, parent, c.name), c.id);
        }
        List<JsonObject> fileCategories = array(root.get("categories"));
        for (boolean childrenPass : new boolean[] {false, true}) {
            for (JsonObject n : fileCategories) {
                String parent = !Text.trim(text(n, "parent")).isEmpty() ? text(n, "parent") : null;
                if ((parent != null) != childrenPass) {
                    continue;
                }
                String k = categoryKey(text(n, "type"), parent, text(n, "name"));
                if (categories.containsKey(k)) {
                    continue;
                }
                CategoryRec c = new CategoryRec();
                c.id = Ids.newId();
                c.name = Text.trim(text(n, "name"));
                c.type = oneOf(n.get("type"), TYPES, "EXPENSE");
                c.color = text(n, "color", "#1B5E3F");
                c.icon = text(n, "icon", "tag");
                c.archived = bool(n, "archived");
                c.parentId = parent == null ? null : categories.get(categoryKey(text(n, "type"), null, parent));
                c.createdAt = createdAt;
                categories.put(k, c.id);
                plan.categories.add(c);
            }
        }
        counts.categories = plan.categories.size();

        // Regras: a palavra-chave é única; as que já existem ficam como estão.
        Set<String> keywords = new HashSet<>();
        for (CategoryRuleRec r : s.list(Entity.CATEGORY_RULE)) {
            keywords.add(r.keyword);
        }
        for (JsonObject n : array(root.get("categoryRules"))) {
            String category = categoryByPath(categories, n.get("category"));
            String keyword = Text.truncate(Text.normalize(text(n, "keyword")), 120);
            if (category == null || keyword.isEmpty() || keywords.contains(keyword)) {
                continue;
            }
            keywords.add(keyword);
            CategoryRuleRec r = new CategoryRuleRec();
            r.id = Ids.newId();
            r.categoryId = category;
            r.keyword = keyword;
            r.createdAt = createdAt;
            plan.rules.add(r);
        }
        counts.categoryRules = plan.rules.size();

        // Recorrentes: igual = mesma descrição, tipo, frequência, data inicial e valor.
        Map<String, String> bills = new HashMap<>();
        for (RecurringBillRec b : s.list(Entity.RECURRING_BILL)) {
            bills.put(billKey(b.description, b.type, b.frequency, b.startDate, b.amount), b.id);
        }
        Map<Integer, String> billIds = new HashMap<>();
        List<JsonObject> fileBills = array(root.get("recurringBills"));
        for (int i = 0; i < fileBills.size(); i++) {
            JsonObject n = fileBills.get(i);
            long amount = cents(n, "amount", 0);
            String startDate = date(n.get("startDate"));
            if (startDate == null) {
                startDate = "";
            }
            String found = bills.get(billKey(text(n, "description"), text(n, "type"), text(n, "frequency"), startDate,
                amount));
            if (found != null) {
                billIds.put(i, found);
                continue;
            }
            RecurringBillRec b = new RecurringBillRec();
            b.id = Ids.newId();
            b.accountId = accounts.get(key(text(n, "account")));
            b.categoryId = categoryByPath(categories, n.get("category"));
            b.description = text(n, "description");
            b.amount = amount;
            b.type = oneOf(n.get("type"), TYPES, "EXPENSE");
            b.frequency = oneOf(n.get("frequency"), FREQUENCIES, "MONTHLY");
            b.startDate = startDate;
            b.endDate = date(n.get("endDate"));
            b.occurrences = intOrNull(n.get("occurrences"));
            b.notes = textOrNull(n, "notes");
            JsonElement active = n.get("active");
            b.active = active != null && active.isJsonPrimitive() && active.getAsJsonPrimitive().isBoolean()
                ? active.getAsBoolean() : true;
            b.startNumber = intOf(n.get("startNumber"), 1);
            b.matchStrictness = intOf(n.get("matchStrictness"), 5);
            b.createdAt = createdAt;
            billIds.put(i, b.id);
            plan.bills.add(b);
        }
        counts.recurringBills = plan.bills.size();

        // Lançamentos: pula o que já existe (identificador do extrato na mesma conta, ou mesma
        // conta+data+tipo+valor+descrição, ou a mesma ocorrência de uma recorrente).
        Set<String> fingerprints = new HashSet<>();
        Set<String> externalIds = new HashSet<>();
        Set<String> occurrences = new HashSet<>();
        for (TransactionRec t : s.list(Entity.TRANSACTION)) {
            fingerprints.add(fingerprint(t.accountId, t.dueDate, t.type, t.amount, t.description));
            if (t.externalId != null) {
                externalIds.add(t.accountId + ":" + t.externalId);
            }
            if (t.recurringBillId != null && t.scheduledDate != null) {
                occurrences.add(t.recurringBillId + ":" + t.scheduledDate);
            }
        }
        List<JsonObject> all = array(root.get("transactions"));
        Map<Integer, TransactionRec> txByRef = new HashMap<>();
        Set<String> usedIds = new HashSet<>();
        for (int i = 0; i < all.size(); i++) {
            JsonObject n = all.get(i);
            String account = accounts.get(key(text(n, "account")));
            if (account == null) {
                throw new Ctx.BusinessException("Conta \"" + text(n, "account") + "\" não encontrada no arquivo");
            }
            String externalId = textOrNull(n, "externalId");
            Integer billRef = intOrNull(n.get("recurringBill"));
            String bill = billRef == null ? null : billIds.get(billRef);
            String scheduled = date(n.get("scheduledDate"));
            String dueDate = date(n.get("date"));
            if (dueDate == null) {
                dueDate = "";
            }
            long amount = cents(n, "amount", 0);
            // Mesma avaliação em curto-circuito do servidor: a primeira chave repetida pula sem registrar as demais.
            boolean duplicate = (externalId != null && !externalIds.add(account + ":" + externalId))
                || (bill != null && scheduled != null && !occurrences.add(bill + ":" + scheduled))
                || !fingerprints.add(fingerprint(account, dueDate, text(n, "type"), amount, text(n, "description")));
            if (duplicate) {
                counts.skippedTransactions++;
                continue;
            }
            TransactionRec t = TransactionsService.emptyTransaction();
            // Ocorrência de recorrente usa o id determinístico (regra + data prevista), para o gerador não criar outra.
            if (bill != null && scheduled != null) {
                String id = Ids.occurrenceId(bill, scheduled);
                if (!s.known(Entity.TRANSACTION, id) && !usedIds.contains(id)) {
                    t.id = id;
                }
            }
            t.accountId = account;
            t.categoryId = categoryByPath(categories, n.get("category"));
            t.recurringBillId = bill;
            t.description = text(n, "description");
            t.amount = amount;
            t.type = oneOf(n.get("type"), TYPES, "EXPENSE");
            t.dueDate = dueDate;
            t.paid = bool(n, "paid");
            t.notes = textOrNull(n, "notes");
            t.externalId = externalId;
            t.installmentNumber = intOrNull(n.get("installmentNumber"));
            t.scheduledDate = scheduled;
            t.merchant = textOrNull(n, "merchant");
            t.receiptAccessKey = textOrNull(n, "receiptAccessKey");
            JsonElement cardAccount = n.get("cardAccount");
            if (cardAccount != null && !cardAccount.isJsonNull()) {
                t.cardAccountId = accounts.get(key(text(n, "cardAccount")));
            }
            String month = textOrNull(n, "invoiceMonth");
            t.cardInvoiceMonth = month == null ? null : month.substring(0, Math.min(7, month.length())) + "-01";
            List<ItemRec> items = new ArrayList<>();
            for (JsonObject it : array(n.get("items"))) {
                JsonElement unit = it.get("unitPrice");
                items.add(new ItemRec(
                    text(it, "description"),
                    numberOrNull(it.get("quantity"), "quantity"),
                    unit == null || unit.isJsonNull() ? null : cents(it, "unitPrice", 0),
                    cents(it, "total", 0)));
            }
            t.items = items;
            t.createdAt = createdAt;
            usedIds.add(t.id);
            String receipt = textOrNull(n, "receipt");
            if (receipt != null) {
                plan.receiptOf.put(t, receipt);
                plan.receiptOrder.add(t);
            }
            txByRef.put(i, t);
            plan.transactions.add(t);
        }
        counts.transactions = plan.transactions.size();

        // Transferências: só quando as duas pernas entram agora.
        for (int i = 0; i < all.size(); i++) {
            Integer toRef = intOrNull(all.get(i).get("transferTo"));
            if (toRef == null) {
                continue;
            }
            TransactionRec from = txByRef.get(i);
            TransactionRec to = txByRef.get(toRef);
            if (from == null || to == null || from == to || from.transferId != null || to.transferId != null) {
                continue;
            }
            TransferRec tr = new TransferRec();
            tr.id = Ids.newId();
            tr.fromTransactionId = from.id;
            tr.toTransactionId = to.id;
            tr.createdAt = createdAt;
            from.transferId = tr.id;
            to.transferId = tr.id;
            plan.transfers.add(tr);
        }
        counts.transfers = plan.transfers.size();

        return plan;
    }

    // ---------- Chaves de casamento ----------

    private static String key(String s) {
        return s == null ? "" : Text.trim(s).toLowerCase(Locale.ROOT);
    }

    private static String categoryKey(String type, String parent, String name) {
        return key(type) + "|" + key(parent) + "|" + key(name);
    }

    /** "Pai › Filha" ou "Nome" do arquivo → id da categoria (qualquer tipo com esse caminho). */
    private static String categoryByPath(Map<String, String> categories, JsonElement v) {
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
            return null;
        }
        String path = v.getAsString();
        if (Text.trim(path).isEmpty()) {
            return null;
        }
        int sep = path.indexOf(SEP);
        String parent = sep < 0 ? null : path.substring(0, sep);
        String name = sep < 0 ? path : path.substring(sep + SEP.length());
        for (String type : TYPES) {
            String id = categories.get(categoryKey(type, parent, name));
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    private static String billKey(String description, String type, String frequency, String startDate, long amount) {
        return key(description) + "|" + key(type) + "|" + key(frequency) + "|" + startDate + "|" + amount;
    }

    private static String fingerprint(String account, String date, String type, long amount, String description) {
        return account + "|" + date + "|" + key(type) + "|" + amount + "|" + key(description);
    }

    // ---------- Leitura tolerante do JSON ----------

    private static List<JsonObject> array(JsonElement v) {
        List<JsonObject> out = new ArrayList<>();
        if (v != null && v.isJsonArray()) {
            for (JsonElement e : v.getAsJsonArray()) {
                if (e != null && e.isJsonObject()) {
                    out.add(e.getAsJsonObject());
                }
            }
        }
        return out;
    }

    /** String(v) do JavaScript para valores do JSON. */
    private static String jsString(JsonElement v) {
        if (v.isJsonPrimitive()) {
            JsonPrimitive p = v.getAsJsonPrimitive();
            if (p.isString()) {
                return p.getAsString();
            }
            if (p.isBoolean()) {
                return p.getAsBoolean() ? "true" : "false";
            }
            return jsNumber(p.getAsDouble());
        }
        if (v.isJsonObject()) {
            return "[object Object]";
        }
        StringBuilder sb = new StringBuilder();
        JsonArray a = v.getAsJsonArray();
        for (int i = 0; i < a.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            JsonElement e = a.get(i);
            if (e != null && !e.isJsonNull()) {
                sb.append(jsString(e));
            }
        }
        return sb.toString();
    }

    private static String jsNumber(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e21) {
            return new BigDecimal(d).toPlainString();
        }
        String s = Double.toString(d);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String text(JsonObject n, String field) {
        return text(n, field, "");
    }

    private static String text(JsonObject n, String field, String fallback) {
        JsonElement v = n.get(field);
        return v == null || v.isJsonNull() ? fallback : jsString(v);
    }

    private static String textOrNull(JsonObject n, String field) {
        JsonElement v = n.get(field);
        return v == null || v.isJsonNull() ? null : jsString(v);
    }

    private static boolean bool(JsonObject n, String field) {
        JsonElement v = n.get(field);
        if (v == null || !v.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive p = v.getAsJsonPrimitive();
        return p.isBoolean() ? p.getAsBoolean() : p.isString() && "true".equals(p.getAsString());
    }

    private static String oneOf(JsonElement v, List<String> allowed, String fallback) {
        if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && allowed.contains(v.getAsString())) {
            return v.getAsString();
        }
        return fallback;
    }

    private static final Pattern JS_DECIMAL = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");

    /** Number(v) do JavaScript (o essencial). */
    private static double toNumber(JsonElement v) {
        if (v.isJsonPrimitive()) {
            JsonPrimitive p = v.getAsJsonPrimitive();
            if (p.isNumber()) {
                return p.getAsDouble();
            }
            if (p.isBoolean()) {
                return p.getAsBoolean() ? 1 : 0;
            }
            String s = Text.trim(p.getAsString());
            if (s.isEmpty()) {
                return 0;
            }
            if (JS_DECIMAL.matcher(s).matches()) {
                return Double.parseDouble(s);
            }
            if (s.equals("Infinity") || s.equals("+Infinity")) {
                return Double.POSITIVE_INFINITY;
            }
            if (s.equals("-Infinity")) {
                return Double.NEGATIVE_INFINITY;
            }
            return Double.NaN;
        }
        if (v.isJsonArray()) {
            JsonArray a = v.getAsJsonArray();
            if (a.size() == 0) {
                return 0;
            }
            if (a.size() == 1 && a.get(0) != null && !a.get(0).isJsonNull()) {
                return toNumber(a.get(0));
            }
        }
        return Double.NaN;
    }

    private static Double numberOrNull(JsonElement v, String field) {
        if (v == null || v.isJsonNull()) {
            return null;
        }
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && v.getAsString().isEmpty()) {
            return null;
        }
        double x = toNumber(v);
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            throw new Ctx.BusinessException("Valor inválido no arquivo (" + field + "): " + jsString(v));
        }
        return x;
    }

    /** Valor em reais do arquivo (número ou texto, como o BigDecimal do servidor) → centavos. */
    private static long cents(JsonObject n, String field, long fallback) {
        Double x = numberOrNull(n.get(field), field);
        return x == null ? fallback : Money.toCents(x);
    }

    private static double intValue(JsonElement v) {
        if (v == null || !v.isJsonPrimitive()) {
            return Double.NaN;
        }
        JsonPrimitive p = v.getAsJsonPrimitive();
        if (p.isNumber()) {
            return p.getAsDouble();
        }
        if (p.isString() && !Text.trim(p.getAsString()).isEmpty()) {
            return toNumber(p);
        }
        return Double.NaN;
    }

    private static int intOf(JsonElement v, int fallback) {
        double x = intValue(v);
        return Double.isNaN(x) || Double.isInfinite(x) ? fallback : (int) x;
    }

    private static Integer intOrNull(JsonElement v) {
        if (v == null || v.isJsonNull()) {
            return null;
        }
        double x = intValue(v);
        return Double.isNaN(x) || Double.isInfinite(x) ? null : (int) x;
    }

    /** "AAAA-MM-DD" (aceita data-hora, como o servidor: só os 10 primeiros caracteres). */
    private static String date(JsonElement v) {
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
            return null;
        }
        String s = v.getAsString();
        if (Text.trim(s).isEmpty()) {
            return null;
        }
        return s.substring(0, Math.min(10, s.length()));
    }
}
