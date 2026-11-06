package br.com.contaslt.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistência em memória (testes e diagnóstico). Mesmas regras da versão Room. */
public class MemoryPersistence implements Persistence {

    private final Map<String, StoredRow> records = new LinkedHashMap<>();
    private final Map<String, StoredRow> outbox = new LinkedHashMap<>();
    private final Map<String, String> meta = new HashMap<>();

    @Override
    public synchronized List<StoredRow> loadAll() {
        return new ArrayList<>(records.values());
    }

    @Override
    public synchronized void write(List<StoredRow> batch) {
        for (StoredRow r : batch) {
            records.put(r.id, r);
            outbox.put(r.id, r);
        }
    }

    @Override
    public synchronized void applyRemote(List<StoredRow> accepted) {
        for (StoredRow r : accepted) {
            records.put(r.id, r);
            StoredRow pending = outbox.get(r.id);
            if (pending != null && pending.updatedAt <= r.updatedAt) {
                outbox.remove(r.id);
            }
        }
    }

    @Override
    public synchronized List<StoredRow> pendingBatch(int limit) {
        List<StoredRow> all = new ArrayList<>(outbox.values());
        all.sort(Comparator.comparingLong(r -> r.updatedAt));
        return new ArrayList<>(all.subList(0, Math.min(limit, all.size())));
    }

    @Override
    public synchronized void acknowledge(List<StoredRow> sent) {
        for (StoredRow s : sent) {
            StoredRow now = outbox.get(s.id);
            if (now != null && now.updatedAt == s.updatedAt) {
                outbox.remove(s.id);
            }
        }
    }

    @Override
    public synchronized int pendingCount() {
        return outbox.size();
    }

    @Override
    public synchronized String getMeta(String key) {
        return meta.get(key);
    }

    @Override
    public synchronized void setMeta(String key, String value) {
        meta.put(key, value);
    }

    @Override
    public void close() {
    }
}
