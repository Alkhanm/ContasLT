package br.com.contaslt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import db.migration.V13__MigrateToSyncRecords;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** V13 converte as tabelas antigas em documentos de sync_records, mantendo as referências entre eles. */
class SyncMigrationTest {

    private final ObjectMapper json = JsonMapper.builder().build();

    @Test
    void convertsRelationalRowsIntoSyncDocuments() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        Flyway.configure().dataSource(ds).target("11").load().migrate();

        JdbcTemplate db = new JdbcTemplate(ds);
        db.update("INSERT INTO users (id, name, email, password_hash, created_at) VALUES (1, 'Ana', 'a@x', 'h', NOW())");
        db.update("INSERT INTO accounts (id, user_id, name, type, initial_balance, color, archived, created_at) "
            + "VALUES (10, 1, 'Nubank', 'CHECKING', 1234.56, '#000000', FALSE, NOW())");
        db.update("INSERT INTO accounts (id, user_id, name, type, initial_balance, color, archived, closing_day, due_day, created_at) "
            + "VALUES (11, 1, 'Cartão', 'CREDIT_CARD', 0, '#000000', FALSE, 3, 10, NOW())");
        db.update("INSERT INTO categories (id, user_id, name, type, color, icon, archived, created_at) "
            + "VALUES (20, 1, 'Casa', 'EXPENSE', '#111111', 'home', FALSE, NOW())");
        db.update("INSERT INTO categories (id, user_id, name, type, color, icon, archived, parent_id, created_at) "
            + "VALUES (21, 1, 'Energia', 'EXPENSE', '#111111', 'zap', FALSE, 20, NOW())");
        db.update("INSERT INTO category_rules (id, user_id, category_id, keyword, created_at) VALUES (30, 1, 21, 'enel', NOW())");
        db.update("INSERT INTO recurring_bills (id, user_id, account_id, category_id, description, amount, type, frequency, "
            + "start_date, active, created_at, start_number, match_strictness) "
            + "VALUES (40, 1, 10, 21, 'Luz', 150.00, 'EXPENSE', 'MONTHLY', '2026-01-05', TRUE, NOW(), 1, 5)");
        db.update("INSERT INTO transactions (id, user_id, account_id, category_id, recurring_bill_id, description, amount, type, "
            + "due_date, scheduled_date, paid, created_at, updated_at, linked_import) "
            + "VALUES (50, 1, 10, 21, 40, 'Luz', 149.90, 'EXPENSE', '2026-01-06', '2026-01-05', TRUE, NOW(), NOW(), FALSE)");
        db.update("INSERT INTO transactions (id, user_id, account_id, description, amount, type, due_date, paid, created_at, updated_at, linked_import) "
            + "VALUES (51, 1, 10, 'Pix entrada', 149.90, 'INCOME', '2026-01-06', TRUE, NOW(), NOW(), FALSE)");
        db.update("INSERT INTO transaction_items (id, transaction_id, position, description, quantity, unit_price, total) "
            + "VALUES (60, 50, 0, 'kWh', 1.5, 99.93, 149.90)");
        db.update("INSERT INTO transfers (id, user_id, from_transaction_id, to_transaction_id, created_at) VALUES (70, 1, 50, 51, NOW())");
        db.update("UPDATE transactions SET transfer_id = 70 WHERE id IN (50, 51)");
        db.update("INSERT INTO transfer_rejections (id, user_id, a_key, b_key, created_at) VALUES (80, 1, 'bill:40', 'tx:51', NOW())");
        db.update("INSERT INTO receipt_jobs (id, user_id, transaction_id, receipt_file, status, attempts, delivered, created_at) "
            + "VALUES (90, 1, 50, 'f.jpg', 'DONE', 1, TRUE, NOW())");

        Flyway.configure().dataSource(ds).load().migrate();

        Map<String, JsonNode> byId = new HashMap<>();
        Map<String, String> entity = new HashMap<>();
        for (Map<String, Object> row : db.queryForList("SELECT * FROM sync_records WHERE user_id = 1")) {
            String id = row.get("id").toString();
            byId.put(id, json.readTree((String) row.get("data")));
            entity.put(id, (String) row.get("entity"));
            assertThat((Long) row.get("version")).isPositive();
        }
        assertThat(byId).hasSize(10);

        String acc = V13__MigrateToSyncRecords.uuid("accounts", 10).toString();
        assertThat(entity.get(acc)).isEqualTo("account");
        assertThat(byId.get(acc).get("initialBalance").asLong()).isEqualTo(123456);
        assertThat(byId.get(V13__MigrateToSyncRecords.uuid("accounts", 11).toString()).get("closingDay").asInt()).isEqualTo(3);

        JsonNode energia = byId.get(V13__MigrateToSyncRecords.uuid("categories", 21).toString());
        assertThat(energia.get("parentId").asString()).isEqualTo(V13__MigrateToSyncRecords.uuid("categories", 20).toString());

        JsonNode tx = byId.get(V13__MigrateToSyncRecords.uuid("transactions", 50).toString());
        assertThat(tx.get("amount").asLong()).isEqualTo(14990);
        assertThat(tx.get("accountId").asString()).isEqualTo(acc);
        assertThat(tx.get("recurringBillId").asString()).isEqualTo(V13__MigrateToSyncRecords.uuid("recurring_bills", 40).toString());
        assertThat(tx.get("dueDate").asString()).isEqualTo("2026-01-06");
        assertThat(tx.get("scheduledDate").asString()).isEqualTo("2026-01-05");
        assertThat(tx.get("transferId").asString()).isEqualTo(V13__MigrateToSyncRecords.uuid("transfers", 70).toString());
        assertThat(tx.get("items")).hasSize(1);
        assertThat(tx.get("items").get(0).get("total").asLong()).isEqualTo(14990);
        assertThat(tx.get("items").get(0).get("quantity").asDouble()).isEqualTo(1.5);
        assertThat(tx.get("cardInvoiceMonth").isNull()).isTrue();

        JsonNode rej = byId.get(V13__MigrateToSyncRecords.uuid("transfer_rejections", 80).toString());
        List<String> keys = List.of(rej.get("aKey").asString(), rej.get("bKey").asString());
        assertThat(keys).containsExactlyInAnyOrder(
            "bill:" + V13__MigrateToSyncRecords.uuid("recurring_bills", 40),
            "tx:" + V13__MigrateToSyncRecords.uuid("transactions", 51));
        assertThat(keys.get(0)).isLessThan(keys.get(1));

        Object jobTx = db.queryForObject("SELECT transaction_uuid FROM receipt_jobs WHERE id = 90", Object.class);
        assertThat(jobTx.toString()).isEqualTo(V13__MigrateToSyncRecords.uuid("transactions", 50).toString());
    }
}
