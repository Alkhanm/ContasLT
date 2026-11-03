package br.com.contaslt.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.contaslt.common.BusinessException;
import br.com.contaslt.receipt.ReceiptStorage;
import br.com.contaslt.user.User;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exportação e importação dos dados de uma conta: contas, categorias, regras, recorrentes e lançamentos (com itens,
 * fotos e transferências). Não é um dump do banco: o arquivo não carrega ids, as referências são por nome (conta,
 * categoria "Pai › Filha") e por posição dentro do próprio arquivo (par de uma transferência, regra recorrente).
 * A importação SOMA ao que já existe: contas e categorias são casadas pelo nome e criadas se faltarem; lançamento
 * igual a um já existente (mesma conta, data, tipo, valor e descrição, ou mesmo identificador de extrato) é pulado.
 * Importar o mesmo arquivo duas vezes não duplica nada.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    public static final String FORMAT = "contaslt-data";
    public static final int VERSION = 1;
    static final String JSON_ENTRY = "contaslt.json";
    static final String RECEIPTS_DIR = "receipts/";
    private static final int BATCH = 500;
    private static final Pattern RECEIPT_NAME = Pattern.compile("u\\d+-([0-9a-f-]{36}\\.jpg)");
    private static final String SEP = " › ";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ReceiptStorage storage;
    private final ObjectMapper json;

    public BackupService(JdbcTemplate jdbc, TransactionTemplate tx, ReceiptStorage storage, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.storage = storage;
        this.json = json;
    }

    // ---------- Exportação ----------

    /** Zip com o JSON primeiro (a importação lê nessa ordem) e as fotos dos comprovantes depois. */
    public void export(User user, OutputStream out) throws IOException {
        ObjectNode root = snapshot(user);
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.putNextEntry(new ZipEntry(JSON_ENTRY));
        zip.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        zip.closeEntry();
        for (JsonNode t : root.get("transactions")) {
            JsonNode f = t.get("receipt");
            if (f == null || f.isNull()) {
                continue;
            }
            storage.load(user.getId(), f.asString()).ifPresent(bytes -> {
                try {
                    zip.putNextEntry(new ZipEntry(RECEIPTS_DIR + f.asString()));
                    zip.write(bytes);
                    zip.closeEntry();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
        zip.finish();
    }

    ObjectNode snapshot(User user) {
        Long uid = user.getId();
        ObjectNode root = json.createObjectNode();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        root.put("exportedAt", LocalDateTime.now().toString());
        root.put("app", "ContasLT");

        Map<Long, String> accountNames = new HashMap<>();
        ArrayNode accounts = root.putArray("accounts");
        for (Map<String, Object> r : jdbc.queryForList("select id, name, type, initial_balance, color, archived, closing_day, due_day"
                + " from accounts where user_id = ? order by id", uid)) {
            accountNames.put(((Number) r.get("id")).longValue(), (String) r.get("name"));
            ObjectNode a = accounts.addObject();
            a.put("name", (String) r.get("name"));
            a.put("type", (String) r.get("type"));
            a.put("initialBalance", (BigDecimal) r.get("initial_balance"));
            a.put("color", (String) r.get("color"));
            a.put("archived", (Boolean) r.get("archived"));
            putInt(a, "closingDay", r.get("closing_day"));
            putInt(a, "dueDay", r.get("due_day"));
        }

        Map<Long, String> categoryPaths = new HashMap<>();
        Map<Long, String> categoryNames = new HashMap<>();
        List<Map<String, Object>> cats = jdbc.queryForList("select id, parent_id, name, type, color, icon, archived"
            + " from categories where user_id = ? order by parent_id nulls first, id", uid);
        for (Map<String, Object> r : cats) {
            categoryNames.put(((Number) r.get("id")).longValue(), (String) r.get("name"));
        }
        ArrayNode categories = root.putArray("categories");
        for (Map<String, Object> r : cats) {
            Long id = ((Number) r.get("id")).longValue();
            String parent = r.get("parent_id") == null ? null : categoryNames.get(((Number) r.get("parent_id")).longValue());
            categoryPaths.put(id, parent == null ? (String) r.get("name") : parent + SEP + r.get("name"));
            ObjectNode c = categories.addObject();
            c.put("name", (String) r.get("name"));
            c.put("type", (String) r.get("type"));
            c.put("color", (String) r.get("color"));
            c.put("icon", (String) r.get("icon"));
            c.put("archived", (Boolean) r.get("archived"));
            c.put("parent", parent);
        }

        ArrayNode rules = root.putArray("categoryRules");
        for (Map<String, Object> r : jdbc.queryForList("select keyword, category_id from category_rules where user_id = ? order by id", uid)) {
            rules.addObject().put("keyword", (String) r.get("keyword")).put("category", categoryPaths.get(((Number) r.get("category_id")).longValue()));
        }

        Map<Long, Integer> billRefs = new HashMap<>();
        ArrayNode bills = root.putArray("recurringBills");
        for (Map<String, Object> r : jdbc.queryForList("select id, account_id, category_id, description, amount, type, frequency, start_date,"
                + " end_date, occurrences, notes, active, start_number, match_strictness from recurring_bills where user_id = ? order by id", uid)) {
            billRefs.put(((Number) r.get("id")).longValue(), bills.size());
            ObjectNode b = bills.addObject();
            b.put("description", (String) r.get("description"));
            b.put("amount", (BigDecimal) r.get("amount"));
            b.put("type", (String) r.get("type"));
            b.put("frequency", (String) r.get("frequency"));
            b.put("startDate", r.get("start_date").toString());
            b.put("endDate", r.get("end_date") == null ? null : r.get("end_date").toString());
            putInt(b, "occurrences", r.get("occurrences"));
            b.put("notes", (String) r.get("notes"));
            b.put("active", (Boolean) r.get("active"));
            b.put("startNumber", ((Number) r.get("start_number")).intValue());
            b.put("matchStrictness", ((Number) r.get("match_strictness")).intValue());
            b.put("account", r.get("account_id") == null ? null : accountNames.get(((Number) r.get("account_id")).longValue()));
            b.put("category", r.get("category_id") == null ? null : categoryPaths.get(((Number) r.get("category_id")).longValue()));
        }

        // Transferências: a perna de saída aponta para a de entrada pela posição no arquivo.
        Map<Long, Long> transferPeer = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("select from_transaction_id, to_transaction_id from transfers where user_id = ?", uid)) {
            transferPeer.put(((Number) r.get("from_transaction_id")).longValue(), ((Number) r.get("to_transaction_id")).longValue());
        }
        List<Map<String, Object>> txRows = jdbc.queryForList("select id, account_id, category_id, recurring_bill_id, description, amount, type,"
            + " due_date, paid, notes, external_id, installment_number, scheduled_date, merchant, receipt_file, receipt_access_key,"
            + " card_account_id, card_invoice_month from transactions where user_id = ? order by due_date, id", uid);
        Map<Long, Integer> txRefs = new HashMap<>();
        for (int i = 0; i < txRows.size(); i++) {
            txRefs.put(((Number) txRows.get(i).get("id")).longValue(), i);
        }
        Map<Long, ArrayNode> itemsByTx = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("select i.transaction_id, i.description, i.quantity, i.unit_price, i.total"
                + " from transaction_items i join transactions t on t.id = i.transaction_id where t.user_id = ? order by i.transaction_id, i.position", uid)) {
            ObjectNode item = itemsByTx.computeIfAbsent(((Number) r.get("transaction_id")).longValue(), k -> json.createArrayNode()).addObject();
            item.put("description", (String) r.get("description"));
            item.put("quantity", (BigDecimal) r.get("quantity"));
            item.put("unitPrice", (BigDecimal) r.get("unit_price"));
            item.put("total", (BigDecimal) r.get("total"));
        }
        ArrayNode transactions = root.putArray("transactions");
        for (Map<String, Object> r : txRows) {
            Long id = ((Number) r.get("id")).longValue();
            ObjectNode t = transactions.addObject();
            t.put("description", (String) r.get("description"));
            t.put("amount", (BigDecimal) r.get("amount"));
            t.put("type", (String) r.get("type"));
            t.put("date", r.get("due_date").toString());
            t.put("paid", (Boolean) r.get("paid"));
            t.put("account", accountNames.get(((Number) r.get("account_id")).longValue()));
            t.put("category", r.get("category_id") == null ? null : categoryPaths.get(((Number) r.get("category_id")).longValue()));
            t.put("notes", (String) r.get("notes"));
            t.put("externalId", (String) r.get("external_id"));
            t.put("merchant", (String) r.get("merchant"));
            t.put("receipt", (String) r.get("receipt_file"));
            t.put("receiptAccessKey", (String) r.get("receipt_access_key"));
            if (r.get("recurring_bill_id") != null) {
                t.put("recurringBill", billRefs.get(((Number) r.get("recurring_bill_id")).longValue()));
                putInt(t, "installmentNumber", r.get("installment_number"));
                t.put("scheduledDate", r.get("scheduled_date") == null ? null : r.get("scheduled_date").toString());
            }
            if (r.get("card_account_id") != null) {
                t.put("cardAccount", accountNames.get(((Number) r.get("card_account_id")).longValue()));
                t.put("invoiceMonth", r.get("card_invoice_month") == null ? null : YearMonth.from(((Date) r.get("card_invoice_month")).toLocalDate()).toString());
            }
            Long peer = transferPeer.get(id);
            if (peer != null && txRefs.containsKey(peer)) {
                t.put("transferTo", txRefs.get(peer));
            }
            t.set("items", itemsByTx.getOrDefault(id, json.createArrayNode()));
        }
        return root;
    }

    private static void putInt(ObjectNode o, String key, Object v) {
        if (v == null) {
            o.putNull(key);
        } else {
            o.put(key, ((Number) v).intValue());
        }
    }

    // ---------- Importação (soma ao que existe) ----------

    /** Quantos entraram e quantos já existiam. */
    public record Imported(int accounts, int categories, int categoryRules, int recurringBills, int transactions,
            int skippedTransactions, int transfers, int receipts) {
    }

    public Imported restore(User user, InputStream in) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry entry = zip.getNextEntry();
        if (entry == null || !JSON_ENTRY.equals(entry.getName())) {
            throw new BusinessException("Arquivo inválido: esperado o zip exportado pelo ContasLT (com " + JSON_ENTRY + ").");
        }
        JsonNode root = json.readTree(zip.readAllBytes());
        if (!FORMAT.equals(root.path("format").asString(""))) {
            throw new BusinessException("Este arquivo não é uma exportação do ContasLT.");
        }
        if (root.path("version").asInt(0) > VERSION) {
            throw new BusinessException("Arquivo de uma versão mais nova do app. Atualize antes de importar.");
        }
        Merge merge = tx.execute(status -> merge(user, root));

        // Fotos: só as dos lançamentos que entraram, renomeadas para o usuário atual.
        int receipts = 0;
        while ((entry = zip.getNextEntry()) != null) {
            if (!entry.getName().startsWith(RECEIPTS_DIR) || entry.isDirectory()) {
                continue;
            }
            String target = merge.receiptNames().get(entry.getName().substring(RECEIPTS_DIR.length()));
            if (target == null) {
                continue;
            }
            try {
                storage.storeRaw(user.getId(), target, zip.readAllBytes());
                receipts++;
            } catch (IOException e) {
                log.warn("Importação: não consegui gravar a foto {}: {}", target, e.toString());
            }
        }
        Imported c = merge.counts();
        return new Imported(c.accounts(), c.categories(), c.categoryRules(), c.recurringBills(), c.transactions(),
            c.skippedTransactions(), c.transfers(), receipts);
    }

    /** Resultado da parte transacional: contagens e o mapa foto-do-arquivo → nome gravado para o usuário. */
    private record Merge(Imported counts, Map<String, String> receiptNames) {
    }

    private Merge merge(User user, JsonNode root) {
        Long uid = user.getId();

        // Contas pelo nome.
        Map<String, Long> accounts = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("select id, name from accounts where user_id = ?", uid)) {
            accounts.put(key((String) r.get("name")), ((Number) r.get("id")).longValue());
        }
        List<JsonNode> newAccounts = new ArrayList<>();
        for (JsonNode a : root.path("accounts")) {
            if (!accounts.containsKey(key(text(a, "name")))) {
                newAccounts.add(a);
            }
        }
        insert("insert into accounts (user_id, name, type, initial_balance, color, archived, closing_day, due_day, created_at) values (?,?,?,?,?,?,?,?,?)",
            newAccounts, (ps, n) -> {
                ps.setLong(1, uid);
                ps.setString(2, text(n, "name"));
                ps.setString(3, text(n, "type", "CHECKING"));
                ps.setBigDecimal(4, decimal(n, "initialBalance", BigDecimal.ZERO));
                ps.setString(5, text(n, "color", "#1B5E3F"));
                ps.setBoolean(6, bool(n, "archived"));
                setInt(ps, 7, n, "closingDay");
                setInt(ps, 8, n, "dueDay");
                ps.setTimestamp(9, now());
            }).forEach((i, id) -> accounts.put(key(text(newAccounts.get(i), "name")), id));

        // Categorias por tipo + "Pai › Filha" (pais antes das filhas).
        Map<String, Long> categories = new HashMap<>();
        Map<Long, String> existingNames = new HashMap<>();
        List<Map<String, Object>> existing = jdbc.queryForList("select id, parent_id, name, type from categories where user_id = ?", uid);
        for (Map<String, Object> r : existing) {
            existingNames.put(((Number) r.get("id")).longValue(), (String) r.get("name"));
        }
        for (Map<String, Object> r : existing) {
            String parent = r.get("parent_id") == null ? null : existingNames.get(((Number) r.get("parent_id")).longValue());
            categories.put(categoryKey((String) r.get("type"), parent, (String) r.get("name")), ((Number) r.get("id")).longValue());
        }
        int newCategories = 0;
        for (boolean childrenPass : new boolean[] { false, true }) {
            List<JsonNode> group = new ArrayList<>();
            for (JsonNode c : root.path("categories")) {
                boolean child = c.hasNonNull("parent") && !text(c, "parent").isBlank();
                if (child == childrenPass && !categories.containsKey(categoryKey(text(c, "type"), child ? text(c, "parent") : null, text(c, "name")))) {
                    group.add(c);
                }
            }
            Map<Integer, Long> ids = insert("insert into categories (user_id, parent_id, name, type, color, icon, archived, created_at) values (?,?,?,?,?,?,?,?)",
                group, (ps, n) -> {
                    ps.setLong(1, uid);
                    String parent = n.hasNonNull("parent") && !text(n, "parent").isBlank() ? text(n, "parent") : null;
                    setLong(ps, 2, parent == null ? null : categories.get(categoryKey(text(n, "type"), null, parent)));
                    ps.setString(3, text(n, "name"));
                    ps.setString(4, text(n, "type", "EXPENSE"));
                    ps.setString(5, text(n, "color", "#1B5E3F"));
                    ps.setString(6, text(n, "icon", "tag"));
                    ps.setBoolean(7, bool(n, "archived"));
                    ps.setTimestamp(8, now());
                });
            ids.forEach((i, id) -> {
                JsonNode n = group.get(i);
                String parent = n.hasNonNull("parent") && !text(n, "parent").isBlank() ? text(n, "parent") : null;
                categories.put(categoryKey(text(n, "type"), parent, text(n, "name")), id);
            });
            newCategories += ids.size();
        }

        // Regras: a palavra-chave é única por usuário; as que já existem ficam como estão.
        Set<String> keywords = new HashSet<>();
        for (Map<String, Object> r : jdbc.queryForList("select keyword from category_rules where user_id = ?", uid)) {
            keywords.add(key((String) r.get("keyword")));
        }
        List<JsonNode> newRules = new ArrayList<>();
        for (JsonNode r : root.path("categoryRules")) {
            Long cat = categoryByPath(categories, r.get("category"));
            if (cat != null && !text(r, "keyword").isBlank() && keywords.add(key(text(r, "keyword")))) {
                newRules.add(r);
            }
        }
        insert("insert into category_rules (user_id, category_id, keyword, created_at) values (?,?,?,?)", newRules, (ps, n) -> {
            ps.setLong(1, uid);
            ps.setLong(2, categoryByPath(categories, n.get("category")));
            ps.setString(3, text(n, "keyword"));
            ps.setTimestamp(4, now());
        });

        // Recorrentes: igual = mesma descrição, tipo, frequência, data inicial e valor.
        Map<String, Long> bills = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("select id, description, type, frequency, start_date, amount from recurring_bills where user_id = ?", uid)) {
            bills.put(billKey((String) r.get("description"), (String) r.get("type"), (String) r.get("frequency"),
                r.get("start_date").toString(), (BigDecimal) r.get("amount")), ((Number) r.get("id")).longValue());
        }
        List<JsonNode> allBills = new ArrayList<>();
        root.path("recurringBills").forEach(allBills::add);
        Map<Integer, Long> billIds = new HashMap<>();
        List<JsonNode> newBills = new ArrayList<>();
        List<Integer> newBillRefs = new ArrayList<>();
        for (int i = 0; i < allBills.size(); i++) {
            JsonNode b = allBills.get(i);
            Long found = bills.get(billKey(text(b, "description"), text(b, "type"), text(b, "frequency"), text(b, "startDate"), decimal(b, "amount", BigDecimal.ZERO)));
            if (found != null) {
                billIds.put(i, found);
            } else {
                newBills.add(b);
                newBillRefs.add(i);
            }
        }
        insert("insert into recurring_bills (user_id, account_id, category_id, description, amount, type, frequency, start_date, end_date,"
            + " occurrences, notes, active, start_number, match_strictness, created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", newBills, (ps, n) -> {
                ps.setLong(1, uid);
                setLong(ps, 2, accounts.get(key(text(n, "account"))));
                setLong(ps, 3, categoryByPath(categories, n.get("category")));
                ps.setString(4, text(n, "description"));
                ps.setBigDecimal(5, decimal(n, "amount", BigDecimal.ZERO));
                ps.setString(6, text(n, "type", "EXPENSE"));
                ps.setString(7, text(n, "frequency", "MONTHLY"));
                ps.setDate(8, date(n, "startDate"));
                ps.setDate(9, date(n, "endDate"));
                setInt(ps, 10, n, "occurrences");
                ps.setString(11, text(n, "notes", null));
                ps.setBoolean(12, n.path("active").asBoolean(true));
                ps.setInt(13, n.path("startNumber").asInt(1));
                ps.setInt(14, n.path("matchStrictness").asInt(5));
                ps.setTimestamp(15, now());
            }).forEach((i, id) -> billIds.put(newBillRefs.get(i), id));

        // Lançamentos: pula o que já existe (identificador do extrato na mesma conta, ou mesma conta+data+tipo+valor+descrição,
        // ou a mesma ocorrência de uma recorrente).
        Set<String> fingerprints = new HashSet<>();
        Set<String> externalIds = new HashSet<>();
        Set<String> occurrences = new HashSet<>();
        for (Map<String, Object> r : jdbc.queryForList("select account_id, due_date, type, amount, description, external_id, recurring_bill_id, scheduled_date"
                + " from transactions where user_id = ?", uid)) {
            fingerprints.add(fingerprint(((Number) r.get("account_id")).longValue(), r.get("due_date").toString(), (String) r.get("type"),
                (BigDecimal) r.get("amount"), (String) r.get("description")));
            if (r.get("external_id") != null) {
                externalIds.add(r.get("account_id") + ":" + r.get("external_id"));
            }
            if (r.get("recurring_bill_id") != null && r.get("scheduled_date") != null) {
                occurrences.add(r.get("recurring_bill_id") + ":" + r.get("scheduled_date"));
            }
        }
        List<JsonNode> all = new ArrayList<>();
        root.path("transactions").forEach(all::add);
        List<JsonNode> newTx = new ArrayList<>();
        List<Integer> newTxRefs = new ArrayList<>();
        Map<String, String> receiptNames = new HashMap<>();
        int skipped = 0;
        for (int i = 0; i < all.size(); i++) {
            JsonNode t = all.get(i);
            Long account = accounts.get(key(text(t, "account")));
            if (account == null) {
                throw new BusinessException("Conta \"" + text(t, "account") + "\" não encontrada no arquivo");
            }
            String externalId = text(t, "externalId", null);
            Long bill = t.hasNonNull("recurringBill") ? billIds.get(t.get("recurringBill").asInt()) : null;
            String scheduled = text(t, "scheduledDate", null);
            boolean duplicate = (externalId != null && !externalIds.add(account + ":" + externalId))
                || (bill != null && scheduled != null && !occurrences.add(bill + ":" + scheduled))
                || !fingerprints.add(fingerprint(account, text(t, "date"), text(t, "type"), decimal(t, "amount", BigDecimal.ZERO), text(t, "description")));
            if (duplicate) {
                skipped++;
                continue;
            }
            newTx.add(t);
            newTxRefs.add(i);
            String receipt = text(t, "receipt", null);
            if (receipt != null) {
                Matcher m = RECEIPT_NAME.matcher(receipt);
                receiptNames.put(receipt, "u" + uid + "-" + (m.matches() ? m.group(1) : UUID.randomUUID() + ".jpg"));
            }
        }
        Map<Integer, Long> txIds = new HashMap<>();   // posição no arquivo → id novo
        insert("insert into transactions (user_id, account_id, category_id, recurring_bill_id, description, amount, type, due_date, paid, notes,"
            + " external_id, installment_number, scheduled_date, merchant, receipt_file, receipt_access_key, linked_import, card_account_id,"
            + " card_invoice_month, created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", newTx, (ps, n) -> {
                ps.setLong(1, uid);
                ps.setLong(2, accounts.get(key(text(n, "account"))));
                setLong(ps, 3, categoryByPath(categories, n.get("category")));
                setLong(ps, 4, n.hasNonNull("recurringBill") ? billIds.get(n.get("recurringBill").asInt()) : null);
                ps.setString(5, text(n, "description"));
                ps.setBigDecimal(6, decimal(n, "amount", BigDecimal.ZERO));
                ps.setString(7, text(n, "type", "EXPENSE"));
                ps.setDate(8, date(n, "date"));
                ps.setBoolean(9, bool(n, "paid"));
                ps.setString(10, text(n, "notes", null));
                ps.setString(11, text(n, "externalId", null));
                setInt(ps, 12, n, "installmentNumber");
                ps.setDate(13, date(n, "scheduledDate"));
                ps.setString(14, text(n, "merchant", null));
                ps.setString(15, receiptNames.get(text(n, "receipt", null)));
                ps.setString(16, text(n, "receiptAccessKey", null));
                ps.setBoolean(17, false);
                setLong(ps, 18, n.hasNonNull("cardAccount") ? accounts.get(key(text(n, "cardAccount"))) : null);
                String month = text(n, "invoiceMonth", null);
                ps.setDate(19, month == null ? null : Date.valueOf(YearMonth.parse(month).atDay(1)));
                Timestamp now = now();
                ps.setTimestamp(20, now);
                ps.setTimestamp(21, now);
            }).forEach((i, id) -> txIds.put(newTxRefs.get(i), id));

        List<Object[]> items = new ArrayList<>();
        for (int i = 0; i < newTx.size(); i++) {
            Long id = txIds.get(newTxRefs.get(i));
            int position = 0;
            for (JsonNode it : newTx.get(i).path("items")) {
                items.add(new Object[] { id, position++, text(it, "description"), decimal(it, "quantity", null),
                    decimal(it, "unitPrice", null), decimal(it, "total", BigDecimal.ZERO) });
            }
        }
        jdbc.batchUpdate("insert into transaction_items (transaction_id, position, description, quantity, unit_price, total) values (?,?,?,?,?,?)", items);

        // Transferências: só quando as duas pernas entraram agora.
        List<Object[]> transfers = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            JsonNode t = all.get(i);
            if (!t.hasNonNull("transferTo")) {
                continue;
            }
            Long from = txIds.get(i);
            Long to = txIds.get(t.get("transferTo").asInt());
            if (from != null && to != null) {
                transfers.add(new Object[] { uid, from, to, now() });
            }
        }
        int linked = 0;
        for (Object[] row : transfers) {
            Long transferId = jdbc.execute((java.sql.Connection con) -> {
                try (PreparedStatement ps = con.prepareStatement("insert into transfers (user_id, from_transaction_id, to_transaction_id, created_at) values (?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, (Long) row[0]);
                    ps.setLong(2, (Long) row[1]);
                    ps.setLong(3, (Long) row[2]);
                    ps.setTimestamp(4, (Timestamp) row[3]);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        return keys.getLong(1);
                    }
                }
            });
            jdbc.update("update transactions set transfer_id = ? where id in (?, ?)", transferId, row[1], row[2]);
            linked++;
        }

        return new Merge(new Imported(newAccounts.size(), newCategories, newRules.size(), newBills.size(), newTx.size(), skipped, linked, 0), receiptNames);
    }

    // ---------- Chaves de casamento ----------

    private static String key(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private static String categoryKey(String type, String parent, String name) {
        return key(type) + "|" + key(parent) + "|" + key(name);
    }

    /** "Pai › Filha" ou "Nome" do arquivo → id da categoria do usuário (qualquer tipo com esse caminho). */
    private static Long categoryByPath(Map<String, Long> categories, JsonNode pathNode) {
        if (pathNode == null || pathNode.isNull() || pathNode.asString().isBlank()) {
            return null;
        }
        String path = pathNode.asString();
        int sep = path.indexOf(SEP);
        String parent = sep < 0 ? null : path.substring(0, sep);
        String name = sep < 0 ? path : path.substring(sep + SEP.length());
        for (String type : new String[] { "EXPENSE", "INCOME" }) {
            Long id = categories.get(categoryKey(type, parent, name));
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    private static String billKey(String description, String type, String frequency, String startDate, BigDecimal amount) {
        return key(description) + "|" + key(type) + "|" + key(frequency) + "|" + startDate + "|" + amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static String fingerprint(Long account, String date, String type, BigDecimal amount, String description) {
        return account + "|" + date + "|" + key(type) + "|" + amount.setScale(2, RoundingMode.HALF_UP) + "|" + key(description);
    }

    // ---------- Inserção em lote ----------

    @FunctionalInterface
    private interface RowBinder {
        void bind(PreparedStatement ps, JsonNode row) throws SQLException;
    }

    /** Insere em lotes com chaves geradas; devolve posição na lista → id novo. */
    private Map<Integer, Long> insert(String sql, List<JsonNode> rows, RowBinder binder) {
        Map<Integer, Long> ids = new HashMap<>();
        for (int start = 0; start < rows.size(); start += BATCH) {
            List<JsonNode> chunk = rows.subList(start, Math.min(rows.size(), start + BATCH));
            int offset = start;
            jdbc.execute((java.sql.Connection con) -> {
                try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    for (JsonNode row : chunk) {
                        binder.bind(ps, row);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        int i = 0;
                        while (keys.next()) {
                            ids.put(offset + i++, keys.getLong(1));
                        }
                        if (i != chunk.size()) {
                            throw new IllegalStateException("O banco devolveu " + i + " ids para " + chunk.size() + " linhas");
                        }
                    }
                }
                return null;
            });
        }
        return ids;
    }

    // ---------- Leitura tolerante do JSON ----------

    private static String text(JsonNode n, String field) {
        return text(n, field, "");
    }

    private static String text(JsonNode n, String field, String fallback) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? fallback : v.asString();
    }

    private static boolean bool(JsonNode n, String field) {
        return n.path(field).asBoolean(false);
    }

    private static BigDecimal decimal(JsonNode n, String field, BigDecimal fallback) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? fallback : v.isNumber() ? v.decimalValue() : new BigDecimal(v.asString());
    }

    private static Date date(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || v.asString().isBlank() ? null : Date.valueOf(LocalDate.parse(v.asString().substring(0, 10)));
    }

    private static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now());
    }

    private static void setInt(PreparedStatement ps, int index, JsonNode n, String field) throws SQLException {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, v.asInt());
        }
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }
}
