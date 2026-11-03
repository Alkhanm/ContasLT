package br.com.contaslt.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Backup completo de um usuário: zip com {@code contaslt.json} (todas as tabelas dele, ids originais) e a pasta
 * {@code receipts/} com as fotos dos comprovantes. A restauração apaga tudo o que o usuário tem e recria a partir
 * do arquivo, remapeando ids (contas, categorias, lançamentos, transferências...) e renomeando as fotos para o
 * prefixo do usuário atual. Tudo por JDBC em lotes: um backup de alguns milhares de lançamentos entra em poucas
 * idas ao banco, o que importa com o Postgres longe do servidor.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    public static final String FORMAT = "contaslt-backup";
    public static final int VERSION = 1;
    static final String JSON_ENTRY = "contaslt.json";
    static final String RECEIPTS_DIR = "receipts/";
    private static final int BATCH = 500;
    private static final Pattern RECEIPT_NAME = Pattern.compile("u\\d+-([0-9a-f-]{36}\\.jpg)");

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

    /** Escreve o zip no stream: primeiro o JSON (a restauração lê nessa ordem), depois as fotos. */
    public void export(User user, OutputStream out) throws IOException {
        ObjectNode root = snapshot(user);
        List<String> receipts = new ArrayList<>();
        for (JsonNode t : root.get("transactions")) {
            JsonNode f = t.get("receiptFile");
            if (f != null && !f.isNull()) {
                receipts.add(f.asString());
            }
        }
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.putNextEntry(new ZipEntry(JSON_ENTRY));
        zip.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        zip.closeEntry();
        for (String name : receipts) {
            storage.load(user.getId(), name).ifPresent(bytes -> {
                try {
                    zip.putNextEntry(new ZipEntry(RECEIPTS_DIR + name));
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
        root.putObject("user").put("name", user.getName()).put("email", user.getEmail());
        root.set("accounts", rows("select id, name, type, initial_balance, color, archived, closing_day, due_day, created_at"
            + " from accounts where user_id = ? order by id", uid));
        root.set("categories", rows("select id, parent_id, name, type, color, icon, archived, created_at"
            + " from categories where user_id = ? order by id", uid));
        root.set("categoryRules", rows("select id, category_id, keyword, created_at from category_rules where user_id = ? order by id", uid));
        root.set("recurringBills", rows("select id, account_id, category_id, description, amount, type, frequency, start_date, end_date,"
            + " occurrences, notes, active, start_number, match_strictness, created_at from recurring_bills where user_id = ? order by id", uid));
        root.set("importBatches", rows("select id, account_id, source, filename, imported_count, created_at"
            + " from import_batches where user_id = ? order by id", uid));
        root.set("transfers", rows("select id, from_transaction_id, to_transaction_id, created_at from transfers where user_id = ? order by id", uid));
        ArrayNode transactions = rows("select id, account_id, category_id, recurring_bill_id, import_batch_id, transfer_id, description,"
            + " amount, type, due_date, paid, notes, external_id, installment_number, scheduled_date, merchant, receipt_file,"
            + " receipt_access_key, linked_import, card_account_id, card_invoice_month, receipt_scan_status, receipt_scan_total,"
            + " receipt_scan_date, created_at, updated_at from transactions where user_id = ? order by id", uid);
        Map<Long, ArrayNode> itemsByTx = new HashMap<>();
        for (JsonNode i : rows("select i.transaction_id, i.position, i.description, i.quantity, i.unit_price, i.total"
                + " from transaction_items i join transactions t on t.id = i.transaction_id where t.user_id = ? order by i.transaction_id, i.position", uid)) {
            itemsByTx.computeIfAbsent(i.get("transactionId").asLong(), k -> json.createArrayNode()).add(((ObjectNode) i).without("transactionId"));
        }
        for (JsonNode t : transactions) {
            ((ObjectNode) t).set("items", itemsByTx.getOrDefault(t.get("id").asLong(), json.createArrayNode()));
        }
        root.set("transactions", transactions);
        return root;
    }

    /** Linhas como objetos JSON com chaves em camelCase; datas e horários em ISO, decimais como número. */
    private ArrayNode rows(String sql, Long uid) {
        ArrayNode array = json.createArrayNode();
        for (Map<String, Object> row : jdbc.queryForList(sql, uid)) {
            ObjectNode o = array.addObject();
            row.forEach((col, v) -> {
                String key = camel(col);
                switch (v) {
                    case null -> o.putNull(key);
                    case Number n when v instanceof BigDecimal d -> o.put(key, d);
                    case Number n -> o.put(key, n.longValue());
                    case Boolean b -> o.put(key, b);
                    case Date d -> o.put(key, d.toLocalDate().toString());
                    case Timestamp t -> o.put(key, t.toLocalDateTime().toString());
                    default -> o.put(key, v.toString());
                }
            });
        }
        return array;
    }

    private static String camel(String col) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : col.toLowerCase().toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    // ---------- Restauração ----------

    public record Restored(int accounts, int categories, int recurringBills, int transactions, int transfers, int receipts) {
    }

    /**
     * Lê o zip (JSON primeiro), substitui todos os dados do usuário numa transação e depois grava as fotos.
     * Fotos são renomeadas para o prefixo do usuário atual; as que faltarem no zip só deixam o lançamento sem imagem.
     */
    public Restored restore(User user, InputStream in) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry entry = zip.getNextEntry();
        if (entry == null || !JSON_ENTRY.equals(entry.getName())) {
            throw new BusinessException("Arquivo inválido: esperado um backup do ContasLT (zip com " + JSON_ENTRY + ").");
        }
        JsonNode root = json.readTree(zip.readAllBytes());
        if (!FORMAT.equals(root.path("format").asString(""))) {
            throw new BusinessException("Este arquivo não é um backup do ContasLT.");
        }
        if (root.path("version").asInt(0) > VERSION) {
            throw new BusinessException("Backup de uma versão mais nova do app. Atualize antes de restaurar.");
        }
        Restored counts = tx.execute(status -> replaceData(user, root));

        int receipts = 0;
        while ((entry = zip.getNextEntry()) != null) {
            if (!entry.getName().startsWith(RECEIPTS_DIR) || entry.isDirectory()) {
                continue;
            }
            String renamed = renameReceipt(user.getId(), entry.getName().substring(RECEIPTS_DIR.length()));
            if (renamed == null) {
                continue;
            }
            try {
                storage.storeRaw(user.getId(), renamed, zip.readAllBytes());
                receipts++;
            } catch (IOException e) {
                log.warn("Backup: não consegui gravar a foto {}: {}", renamed, e.toString());
            }
        }
        return new Restored(counts.accounts(), counts.categories(), counts.recurringBills(), counts.transactions(), counts.transfers(), receipts);
    }

    /** {@code u<antigo>-<uuid>.jpg} vira {@code u<atual>-<uuid>.jpg}; nome fora do padrão é ignorado. */
    static String renameReceipt(Long userId, String name) {
        if (name == null) {
            return null;
        }
        Matcher m = RECEIPT_NAME.matcher(name);
        return m.matches() ? "u" + userId + "-" + m.group(1) : null;
    }

    private Restored replaceData(User user, JsonNode root) {
        Long uid = user.getId();
        // Apaga na ordem das dependências (itens e jobs caem em cascata com os lançamentos).
        jdbc.update("delete from receipt_jobs where user_id = ?", uid);
        jdbc.update("delete from transfers where user_id = ?", uid);
        jdbc.update("delete from transactions where user_id = ?", uid);
        jdbc.update("delete from import_batches where user_id = ?", uid);
        jdbc.update("delete from recurring_bills where user_id = ?", uid);
        jdbc.update("delete from category_rules where user_id = ?", uid);
        jdbc.update("delete from categories where user_id = ? and parent_id is not null", uid);
        jdbc.update("delete from categories where user_id = ?", uid);
        jdbc.update("delete from accounts where user_id = ?", uid);

        Map<Long, Long> accounts = insert("insert into accounts (user_id, name, type, initial_balance, color, archived, closing_day, due_day, created_at)"
            + " values (?,?,?,?,?,?,?,?,?)", root.path("accounts"), (ps, n) -> {
                ps.setLong(1, uid);
                ps.setString(2, text(n, "name"));
                ps.setString(3, text(n, "type"));
                ps.setBigDecimal(4, decimal(n, "initialBalance", BigDecimal.ZERO));
                ps.setString(5, text(n, "color", "#1B5E3F"));
                ps.setBoolean(6, bool(n, "archived"));
                setInt(ps, 7, n, "closingDay");
                setInt(ps, 8, n, "dueDay");
                ps.setTimestamp(9, timestamp(n, "createdAt"));
            });

        // Categorias: pais primeiro (as filhas precisam do id novo do pai).
        List<JsonNode> roots = new ArrayList<>();
        List<JsonNode> children = new ArrayList<>();
        for (JsonNode c : root.path("categories")) {
            (c.hasNonNull("parentId") ? children : roots).add(c);
        }
        Map<Long, Long> categories = new HashMap<>();
        for (List<JsonNode> group : List.of(roots, children)) {
            categories.putAll(insert("insert into categories (user_id, parent_id, name, type, color, icon, archived, created_at) values (?,?,?,?,?,?,?,?)",
                group, (ps, n) -> {
                    ps.setLong(1, uid);
                    setLong(ps, 2, mapped(categories, n, "parentId"));
                    ps.setString(3, text(n, "name"));
                    ps.setString(4, text(n, "type"));
                    ps.setString(5, text(n, "color", "#1B5E3F"));
                    ps.setString(6, text(n, "icon", "tag"));
                    ps.setBoolean(7, bool(n, "archived"));
                    ps.setTimestamp(8, timestamp(n, "createdAt"));
                }));
        }

        insert("insert into category_rules (user_id, category_id, keyword, created_at) values (?,?,?,?)", root.path("categoryRules"), (ps, n) -> {
            ps.setLong(1, uid);
            ps.setLong(2, required(categories, n, "categoryId", "categoria da regra"));
            ps.setString(3, text(n, "keyword"));
            ps.setTimestamp(4, timestamp(n, "createdAt"));
        });

        Map<Long, Long> bills = insert("insert into recurring_bills (user_id, account_id, category_id, description, amount, type, frequency, start_date,"
            + " end_date, occurrences, notes, active, start_number, match_strictness, created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            root.path("recurringBills"), (ps, n) -> {
                ps.setLong(1, uid);
                setLong(ps, 2, mapped(accounts, n, "accountId"));
                setLong(ps, 3, mapped(categories, n, "categoryId"));
                ps.setString(4, text(n, "description"));
                ps.setBigDecimal(5, decimal(n, "amount", BigDecimal.ZERO));
                ps.setString(6, text(n, "type"));
                ps.setString(7, text(n, "frequency"));
                ps.setDate(8, date(n, "startDate"));
                ps.setDate(9, date(n, "endDate"));
                setInt(ps, 10, n, "occurrences");
                ps.setString(11, text(n, "notes", null));
                ps.setBoolean(12, n.path("active").asBoolean(true));
                ps.setInt(13, n.path("startNumber").asInt(1));
                ps.setInt(14, n.path("matchStrictness").asInt(5));
                ps.setTimestamp(15, timestamp(n, "createdAt"));
            });

        Map<Long, Long> batches = insert("insert into import_batches (user_id, account_id, source, filename, imported_count, created_at) values (?,?,?,?,?,?)",
            root.path("importBatches"), (ps, n) -> {
                ps.setLong(1, uid);
                ps.setLong(2, required(accounts, n, "accountId", "conta da importação"));
                ps.setString(3, text(n, "source"));
                ps.setString(4, text(n, "filename", null));
                ps.setInt(5, n.path("importedCount").asInt(0));
                ps.setTimestamp(6, timestamp(n, "createdAt"));
            });

        // Lançamentos sem o vínculo de transferência (as transferências precisam dos ids novos dos lançamentos).
        Map<Long, Long> transactions = insert("insert into transactions (user_id, account_id, category_id, recurring_bill_id, import_batch_id,"
            + " description, amount, type, due_date, paid, notes, external_id, installment_number, scheduled_date, merchant, receipt_file,"
            + " receipt_access_key, linked_import, card_account_id, card_invoice_month, receipt_scan_status, receipt_scan_total,"
            + " receipt_scan_date, created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            root.path("transactions"), (ps, n) -> {
                ps.setLong(1, uid);
                ps.setLong(2, required(accounts, n, "accountId", "conta do lançamento"));
                setLong(ps, 3, mapped(categories, n, "categoryId"));
                setLong(ps, 4, mapped(bills, n, "recurringBillId"));
                setLong(ps, 5, mapped(batches, n, "importBatchId"));
                ps.setString(6, text(n, "description"));
                ps.setBigDecimal(7, decimal(n, "amount", BigDecimal.ZERO));
                ps.setString(8, text(n, "type"));
                ps.setDate(9, date(n, "dueDate"));
                ps.setBoolean(10, bool(n, "paid"));
                ps.setString(11, text(n, "notes", null));
                ps.setString(12, text(n, "externalId", null));
                setInt(ps, 13, n, "installmentNumber");
                ps.setDate(14, date(n, "scheduledDate"));
                ps.setString(15, text(n, "merchant", null));
                ps.setString(16, renameReceipt(uid, text(n, "receiptFile", null)));
                ps.setString(17, text(n, "receiptAccessKey", null));
                ps.setBoolean(18, bool(n, "linkedImport"));
                setLong(ps, 19, mapped(accounts, n, "cardAccountId"));
                ps.setDate(20, date(n, "cardInvoiceMonth"));
                ps.setString(21, text(n, "receiptScanStatus", null));
                ps.setBigDecimal(22, decimal(n, "receiptScanTotal", null));
                ps.setDate(23, date(n, "receiptScanDate"));
                ps.setTimestamp(24, timestamp(n, "createdAt"));
                ps.setTimestamp(25, timestamp(n, "updatedAt"));
            });

        List<Object[]> items = new ArrayList<>();
        for (JsonNode t : root.path("transactions")) {
            Long newId = transactions.get(t.path("id").asLong());
            int position = 0;
            for (JsonNode i : t.path("items")) {
                items.add(new Object[] { newId, position++, text(i, "description"), decimal(i, "quantity", null),
                    decimal(i, "unitPrice", null), decimal(i, "total", BigDecimal.ZERO) });
            }
        }
        jdbc.batchUpdate("insert into transaction_items (transaction_id, position, description, quantity, unit_price, total) values (?,?,?,?,?,?)", items);

        Map<Long, Long> transfers = insert("insert into transfers (user_id, from_transaction_id, to_transaction_id, created_at) values (?,?,?,?)",
            root.path("transfers"), (ps, n) -> {
                ps.setLong(1, uid);
                ps.setLong(2, required(transactions, n, "fromTransactionId", "lançamento de origem da transferência"));
                ps.setLong(3, required(transactions, n, "toTransactionId", "lançamento de destino da transferência"));
                ps.setTimestamp(4, timestamp(n, "createdAt"));
            });
        List<Object[]> links = new ArrayList<>();
        for (JsonNode t : root.path("transactions")) {
            Long transfer = mapped(transfers, t, "transferId");
            if (transfer != null) {
                links.add(new Object[] { transfer, transactions.get(t.path("id").asLong()) });
            }
        }
        jdbc.batchUpdate("update transactions set transfer_id = ? where id = ?", links);

        return new Restored(accounts.size(), categories.size(), bills.size(), transactions.size(), transfers.size(), 0);
    }

    @FunctionalInterface
    private interface RowBinder {
        void bind(PreparedStatement ps, JsonNode row) throws SQLException;
    }

    /** Insere em lotes com chaves geradas e devolve id antigo → id novo (os ids voltam na ordem dos lotes). */
    private Map<Long, Long> insert(String sql, Iterable<JsonNode> rows, RowBinder binder) {
        Map<Long, Long> ids = new HashMap<>();
        List<JsonNode> pending = new ArrayList<>();
        for (JsonNode row : rows) {
            pending.add(row);
            if (pending.size() == BATCH) {
                ids.putAll(insertBatch(sql, pending, binder));
                pending.clear();
            }
        }
        if (!pending.isEmpty()) {
            ids.putAll(insertBatch(sql, pending, binder));
        }
        return ids;
    }

    private Map<Long, Long> insertBatch(String sql, List<JsonNode> rows, RowBinder binder) {
        return jdbc.execute((java.sql.Connection con) -> {
            try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                for (JsonNode row : rows) {
                    binder.bind(ps, row);
                    ps.addBatch();
                }
                ps.executeBatch();
                Map<Long, Long> ids = new HashMap<>();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    int i = 0;
                    while (keys.next()) {
                        ids.put(rows.get(i++).path("id").asLong(), keys.getLong(1));
                    }
                    if (i != rows.size()) {
                        throw new IllegalStateException("O banco devolveu " + i + " ids para " + rows.size() + " linhas");
                    }
                }
                return ids;
            }
        });
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

    private static Timestamp timestamp(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || v.asString().isBlank() ? Timestamp.valueOf(LocalDateTime.now())
            : Timestamp.valueOf(LocalDateTime.parse(v.asString()));
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

    private static Long mapped(Map<Long, Long> ids, JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : ids.get(v.asLong());
    }

    private static Long required(Map<Long, Long> ids, JsonNode n, String field, String what) {
        Long id = mapped(ids, n, field);
        if (id == null) {
            throw new BusinessException("Backup inconsistente: " + what + " não encontrada (" + field + "=" + n.path(field).asString() + ")");
        }
        return id;
    }
}
