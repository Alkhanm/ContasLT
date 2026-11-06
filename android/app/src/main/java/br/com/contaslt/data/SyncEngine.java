package br.com.contaslt.data;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;

/**
 * Uma rodada de sincronização (porte de frontend/src/data/sync.ts): sobe o outbox em lotes, depois baixa tudo
 * acima do cursor. O que o servidor recusou (ele tinha uma gravação mais nova) volta na resposta e é aplicado.
 */
public final class SyncEngine {

    static final int PUSH_BATCH = 500;
    static final int PULL_PAGE = 1000;
    public static final String CURSOR_KEY = "syncCursor";
    public static final String INITIAL_DONE_KEY = "initialSyncDone";

    private SyncEngine() {
    }

    public record Result(int pushed, int pulled) {
    }

    public static Result syncOnce(LocalStore store, SyncTransport transport) {
        int pushed = 0;
        for (int round = 0; round < 1000; round++) {
            List<StoredRow> batch = store.pendingBatch(PUSH_BATCH);
            if (batch.isEmpty()) {
                break;
            }
            List<SyncTransport.Change> changes = new ArrayList<>();
            for (StoredRow r : batch) {
                SyncTransport.Change c = new SyncTransport.Change();
                c.id = r.id;
                c.entity = r.entity;
                c.data = r.deleted || r.data == null ? null : JsonParser.parseString(r.data);
                c.deleted = r.deleted;
                c.updatedAt = r.updatedAt;
                changes.add(c);
            }
            SyncTransport.PushResponse res = transport.push(changes);
            // Aceito ou recusado, o que subiu sem mudar de novo sai do outbox; o recusado vem na versão do servidor.
            store.acknowledge(batch);
            if (res.rejected != null && !res.rejected.isEmpty()) {
                store.applyRemote(toRows(res.rejected));
            }
            pushed += batch.size();
            if (batch.size() < PUSH_BATCH) {
                break;
            }
        }

        int pulled = 0;
        String saved = store.getMeta(CURSOR_KEY);
        long cursor = saved == null ? 0 : Long.parseLong(saved);
        for (int page = 0; page < 10_000; page++) {
            SyncTransport.PullResponse res = transport.pull(cursor, PULL_PAGE);
            pulled += store.applyRemote(toRows(res.records == null ? java.util.Collections.<SyncTransport.RemoteRecord>emptyList() : res.records));
            cursor = res.cursor;
            store.setMeta(CURSOR_KEY, Long.toString(cursor));
            if (!res.hasMore) {
                break;
            }
        }
        store.setMeta(INITIAL_DONE_KEY, "true");
        return new Result(pushed, pulled);
    }

    private static List<StoredRow> toRows(List<SyncTransport.RemoteRecord> records) {
        List<StoredRow> out = new ArrayList<>(records.size());
        for (SyncTransport.RemoteRecord r : records) {
            out.add(r.toRow());
        }
        return out;
    }
}
