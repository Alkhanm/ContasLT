package db.migration;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converte as tabelas relacionais (contas, categorias, lançamentos...) em documentos de {@code sync_records}, no
 * formato que os clientes local-first usam (ver {@code frontend/src/data/schema.ts}). As tabelas antigas ficam
 * intactas (rollback possível) e deixam de ser usadas.
 *
 * <p>Ids viram UUIDs determinísticos de {@code tabela:id} ({@link #uuid}), assim as referências entre registros se
 * mantêm sem tabela de-para. Valores monetários vão em centavos (inteiro); datas em {@code AAAA-MM-DD}.
 */
public class V13__MigrateToSyncRecords extends BaseJavaMigration {

    private final ObjectMapper json = JsonMapper.builder().build();

    public static UUID uuid(String table, long id) {
        return UUID.nameUUIDFromBytes(("contaslt:" + table + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        long now = System.currentTimeMillis();
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO sync_records (user_id, id, entity, data, deleted, version, updated_at) "
                    + "VALUES (?, ?, ?, ?, FALSE, nextval('sync_version_seq'), ?)")) {
            Writer w = (userId, id, entity, data) -> {
                insert.setLong(1, userId);
                insert.setObject(2, id);
                insert.setString(3, entity);
                insert.setString(4, json.writeValueAsString(data));
                insert.setLong(5, now);
                insert.addBatch();
            };
            accounts(c, w);
            categories(c, w);
            rules(c, w);
            recurring(c, w);
            batches(c, w);
            transactions(c, w);
            transfers(c, w);
            rejections(c, w);
            insert.executeBatch();
        }
        try (Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT id, transaction_id FROM receipt_jobs WHERE transaction_id IS NOT NULL");
            List<long[]> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new long[] {rs.getLong(1), rs.getLong(2)});
            }
            try (PreparedStatement up = c.prepareStatement("UPDATE receipt_jobs SET transaction_uuid = ? WHERE id = ?")) {
                for (long[] r : rows) {
                    up.setObject(1, uuid("transactions", r[1]));
                    up.setLong(2, r[0]);
                    up.addBatch();
                }
                up.executeBatch();
            }
        }
    }

    @FunctionalInterface
    interface Writer {
        void write(long userId, UUID id, String entity, Map<String, Object> data) throws SQLException;
    }

    private void accounts(Connection c, Writer w) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM accounts ORDER BY id")) {
            while (rs.next()) {
                UUID id = uuid("accounts", rs.getLong("id"));
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", id.toString());
                d.put("name", rs.getString("name"));
                d.put("type", rs.getString("type"));
                d.put("initialBalance", cents(rs.getBigDecimal("initial_balance")));
                d.put("color", rs.getString("color"));
                d.put("archived", rs.getBoolean("archived"));
                d.put("closingDay", intOrNull(rs, "closing_day"));
                d.put("dueDay", intOrNull(rs, "due_day"));
                d.put("createdAt", ts(rs.getTimestamp("created_at")));
                w.write(rs.getLong("user_id"), id, "account", d);
            }
        }
    }

    private void categories(Connection c, Writer w) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM categories ORDER BY id")) {
            while (rs.next()) {
                UUID id = uuid("categories", rs.getLong("id"));
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", id.toString());
                d.put("name", rs.getString("name"));
                d.put("type", rs.getString("type"));
                d.put("color", rs.getString("color"));
                d.put("icon", rs.getString("icon"));
                d.put("archived", rs.getBoolean("archived"));
                d.put("parentId", ref(rs, "parent_id", "categories"));
                d.put("createdAt", ts(rs.getTimestamp("created_at")));
                w.write(rs.getLong("user_id"), id, "category", d);
            }
        }
    }

    private void rules(Connection c, Writer w) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM category_rules ORDER BY id")) {
            while (rs.next()) {
                UUID id = uuid("category_rules", rs.getLong("id"));
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", id.toString());
                d.put("categoryId", ref(rs, "category_id", "categories"));
                d.put("keyword", rs.getString("keyword"));
                d.put("createdAt", ts(rs.getTimestamp("created_at")));
                w.write(rs.getLong("user_id"), id, "categoryRule", d);
            }
        }
    }

    private void recurring(Connection c, Writer w) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM recurring_bills ORDER BY id")) {
            while (rs.next()) {
                UUID id = uuid("recurring_bills", rs.getLong("id"));
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", id.toString());
                d.put("accountId", ref(rs, "account_id", "accounts"));
                d.put("categoryId", ref(rs, "category_id", "categories"));
                d.put("description", rs.getString("description"));
                d.put("amount", cents(rs.getBigDecimal("amount")));
                d.put("type", rs.getString("type"));
                d.put("frequency", rs.getString("frequency"));
                d.put("startDate", date(rs.getDate("start_date")));
                d.put("endDate", date(rs.getDate("end_date")));
                d.put("occurrences", intOrNull(rs, "occurrences"));
                d.put("startNumber", rs.getInt("start_number"));
                d.put("matchStrictness", rs.getInt("match_strictness"));
                d.put("notes", rs.getString("notes"));
                d.put("active", rs.getBoolean("active"));
                d.put("createdAt", ts(rs.getTimestamp("created_at")));
                w.write(rs.getLong("user_id"), id, "recurringBill", d);
            }
        }
    }

    private void batches(Connection c, Writer w) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM import_batches ORDER BY id")) {
            while (rs.next()) {
                UUID id = uuid("import_batches", rs.getLong("id"));
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", id.toString());
                d.put("accountId", ref(rs, "account_id", "accounts"));
                d.put("source", rs.getString("source"));
                d.put("filename", rs.getString("filename"));
                d.put("importedCount", rs.getInt("imported_count"));
                d.put("createdAt", ts(rs.getTimestamp("created_at")));
                w.write(rs.getLong("user_id"), id, "importBatch", d);
            }
        }
    }

    private void transactions(Connection c, Writer w) throws SQLException {
        Map<Long, List<Map<String, Object>>> items = new HashMap<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM transaction_items ORDER BY transaction_id, position, id")) {
            while (rs.next()) {
                Map<String, Object> i = new LinkedHashMap<>();
                i.put("description", rs.getString("description"));
                BigDecimal q = rs.getBigDecimal("quantity");
                i.put("quantity", q == null ? null : q.stripTrailingZeros().doubleValue());
                i.put("unitPrice", cents(rs.getBigDecimal("unit_price")));
                i.put("total", cents(rs.getBigDecimal("total")));
                items.computeIfAbsent(rs.getLong("transaction_id"), k -> new ArrayList<>()).add(i);
            }
        }
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM transactions ORDER BY id")) {
            while (rs.next()) {
                long rawId = rs.getLong("id");
                UUID id = uuid("transactions", rawId);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", id.toString());
                d.put("accountId", ref(rs, "account_id", "accounts"));
                d.put("categoryId", ref(rs, "category_id", "categories"));
                d.put("recurringBillId", ref(rs, "recurring_bill_id", "recurring_bills"));
                d.put("importBatchId", ref(rs, "import_batch_id", "import_batches"));
                d.put("transferId", ref(rs, "transfer_id", "transfers"));
                d.put("description", rs.getString("description"));
                d.put("amount", cents(rs.getBigDecimal("amount")));
                d.put("type", rs.getString("type"));
                d.put("dueDate", date(rs.getDate("due_date")));
                d.put("scheduledDate", date(rs.getDate("scheduled_date")));
                d.put("paid", rs.getBoolean("paid"));
                d.put("notes", rs.getString("notes"));
                d.put("externalId", rs.getString("external_id"));
                d.put("installmentNumber", intOrNull(rs, "installment_number"));
                d.put("merchant", rs.getString("merchant"));
                d.put("receiptFile", rs.getString("receipt_file"));
                d.put("receiptAccessKey", rs.getString("receipt_access_key"));
                d.put("linkedImport", rs.getBoolean("linked_import"));
                d.put("receiptScanStatus", rs.getString("receipt_scan_status"));
                d.put("receiptScanTotal", cents(rs.getBigDecimal("receipt_scan_total")));
                d.put("receiptScanDate", date(rs.getDate("receipt_scan_date")));
                d.put("cardAccountId", ref(rs, "card_account_id", "accounts"));
                d.put("cardInvoiceMonth", date(rs.getDate("card_invoice_month")));
                d.put("items", items.getOrDefault(rawId, List.of()));
                d.put("createdAt", ts(rs.getTimestamp("created_at")));
                w.write(rs.getLong("user_id"), id, "transaction", d);
            }
        }
    }

    private void transfers(Connection c, Writer w) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM transfers ORDER BY id")) {
            while (rs.next()) {
                UUID id = uuid("transfers", rs.getLong("id"));
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", id.toString());
                d.put("fromTransactionId", ref(rs, "from_transaction_id", "transactions"));
                d.put("toTransactionId", ref(rs, "to_transaction_id", "transactions"));
                d.put("createdAt", ts(rs.getTimestamp("created_at")));
                w.write(rs.getLong("user_id"), id, "transfer", d);
            }
        }
    }

    private void rejections(Connection c, Writer w) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM transfer_rejections ORDER BY id")) {
            while (rs.next()) {
                UUID id = uuid("transfer_rejections", rs.getLong("id"));
                String a = rejectionKey(rs.getString("a_key"));
                String b = rejectionKey(rs.getString("b_key"));
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", id.toString());
                // a < b continua valendo depois da troca de ids, para a busca não depender da ordem.
                d.put("aKey", a.compareTo(b) <= 0 ? a : b);
                d.put("bKey", a.compareTo(b) <= 0 ? b : a);
                d.put("createdAt", ts(rs.getTimestamp("created_at")));
                w.write(rs.getLong("user_id"), id, "transferRejection", d);
            }
        }
    }

    /** "bill:12" / "tx:34" com os ids novos. */
    static String rejectionKey(String key) {
        int sep = key.indexOf(':');
        String kind = key.substring(0, sep);
        long id = Long.parseLong(key.substring(sep + 1));
        return kind + ":" + uuid(kind.equals("bill") ? "recurring_bills" : "transactions", id);
    }

    private static String ref(ResultSet rs, String column, String table) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : uuid(table, v).toString();
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Long cents(BigDecimal v) {
        return v == null ? null : v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    private static String date(Date d) {
        return d == null ? null : d.toLocalDate().toString();
    }

    private static String ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime().toString();
    }
}
