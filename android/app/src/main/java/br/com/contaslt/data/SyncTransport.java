package br.com.contaslt.data;

import java.util.List;

import com.google.gson.JsonElement;

/** Rotas de sincronização do servidor (/api/sync/pull e /api/sync/push). */
public interface SyncTransport {

    /** Registro como o servidor devolve. */
    final class RemoteRecord {
        public String id;
        public String entity;
        public JsonElement data;
        public boolean deleted;
        public long version;
        public long updatedAt;

        StoredRow toRow() {
            return new StoredRow(id, entity, deleted || data == null || data.isJsonNull() ? null : data.toString(), deleted, updatedAt);
        }
    }

    final class PullResponse {
        public List<RemoteRecord> records;
        public long cursor;
        public boolean hasMore;
    }

    final class Change {
        public String id;
        public String entity;
        public JsonElement data;
        public boolean deleted;
        public long updatedAt;
    }

    final class PushResponse {
        public int accepted;
        public List<RemoteRecord> rejected;
    }

    PullResponse pull(long since, int limit);

    PushResponse push(List<Change> changes);
}
