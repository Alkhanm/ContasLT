package br.com.contaslt.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import br.com.contaslt.data.schema.AccountRec;
import br.com.contaslt.data.schema.Entity;

/** Porte de frontend/src/data/sync.test.ts. */
public class SyncEngineTest {

    /** Servidor falso com a regra do SyncService: vence o updatedAt maior (empate aceita). */
    static class FakeServer implements SyncTransport {
        final Map<String, RemoteRecord> rows = new LinkedHashMap<>();
        long version;

        @Override
        public synchronized PullResponse pull(long since, int limit) {
            List<RemoteRecord> all = new ArrayList<>();
            for (RemoteRecord r : rows.values()) {
                if (r.version > since) {
                    all.add(r);
                }
            }
            all.sort(Comparator.comparingLong(r -> r.version));
            PullResponse res = new PullResponse();
            res.records = new ArrayList<>(all.subList(0, Math.min(limit, all.size())));
            res.cursor = res.records.isEmpty() ? since : res.records.get(res.records.size() - 1).version;
            res.hasMore = res.records.size() == limit;
            return res;
        }

        @Override
        public synchronized PushResponse push(List<Change> changes) {
            PushResponse res = new PushResponse();
            res.rejected = new ArrayList<>();
            for (Change c : changes) {
                RemoteRecord cur = rows.get(c.id);
                if (cur != null && cur.updatedAt > c.updatedAt) {
                    res.rejected.add(cur);
                    continue;
                }
                RemoteRecord r = new RemoteRecord();
                r.id = c.id;
                r.entity = c.entity;
                r.data = c.deleted ? null : c.data;
                r.deleted = c.deleted;
                r.updatedAt = c.updatedAt;
                r.version = ++version;
                rows.put(c.id, r);
                res.accepted++;
            }
            return res;
        }
    }

    static LocalStore device() {
        LocalStore s = new LocalStore(new MemoryPersistence());
        s.load();
        return s;
    }

    static AccountRec account(String id, String name) {
        AccountRec a = new AccountRec();
        a.id = id;
        a.name = name;
        a.type = "CHECKING";
        a.color = "#000";
        a.createdAt = "2026-01-01T00:00";
        return a;
    }

    @Test
    public void levaGravacoesEntreAparelhosERespeitaAMaisNova() throws Exception {
        FakeServer server = new FakeServer();
        LocalStore a = device();
        LocalStore b = device();

        a.put(Entity.ACCOUNT, account("x", "Nubank"));
        SyncEngine.syncOnce(a, server);
        SyncEngine.syncOnce(b, server);
        assertEquals("Nubank", b.get(Entity.ACCOUNT, "x").name);
        assertEquals(0, a.pendingCount());

        a.put(Entity.ACCOUNT, account("x", "Nubank A"));
        Thread.sleep(2);
        b.put(Entity.ACCOUNT, account("x", "Nubank B"));
        SyncEngine.syncOnce(b, server);
        SyncEngine.syncOnce(a, server);
        assertEquals("Nubank B", a.get(Entity.ACCOUNT, "x").name);
        assertEquals(0, a.pendingCount());
        SyncEngine.syncOnce(b, server);
        assertEquals("Nubank B", b.get(Entity.ACCOUNT, "x").name);

        b.remove(Entity.ACCOUNT, "x");
        SyncEngine.syncOnce(b, server);
        SyncEngine.syncOnce(a, server);
        assertNull(a.get(Entity.ACCOUNT, "x"));
        assertTrue(a.known(Entity.ACCOUNT, "x"));
    }

    @Test
    public void vencimentoGeradoNuncaDesfazEdicaoEmOutroAparelho() {
        FakeServer server = new FakeServer();
        LocalStore a = device();
        LocalStore b = device();
        a.put(Entity.ACCOUNT, account("occ", "gerado"), true);
        SyncEngine.syncOnce(a, server);
        a.put(Entity.ACCOUNT, account("occ", "pago"));
        SyncEngine.syncOnce(a, server);
        b.put(Entity.ACCOUNT, account("occ", "gerado"), true);
        SyncEngine.syncOnce(b, server);
        assertEquals("pago", b.get(Entity.ACCOUNT, "occ").name);
        assertEquals("pago", server.rows.get("occ").data.getAsJsonObject().get("name").getAsString());
    }

    @Test
    public void persistenciaGuardaRegistrosEOutbox() {
        MemoryPersistence p = new MemoryPersistence();
        LocalStore s1 = new LocalStore(p);
        s1.load();
        s1.put(Entity.ACCOUNT, account("p", "Caixa"));
        s1.flush();
        LocalStore s2 = new LocalStore(p);
        s2.load();
        assertEquals("Caixa", s2.get(Entity.ACCOUNT, "p").name);
        assertEquals(1, s2.pendingCount());
    }

    @Test
    public void jsonTemOsCamposDoPwaComNulos() {
        AccountRec a = account("j", "Teste");
        String json = Json.GSON.toJson(a);
        assertTrue(json, json.contains("\"closingDay\":null"));
        assertTrue(json, json.contains("\"initialBalance\":0"));
    }
}
