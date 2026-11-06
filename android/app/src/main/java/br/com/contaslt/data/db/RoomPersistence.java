package br.com.contaslt.data.db;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import br.com.contaslt.data.Persistence;
import br.com.contaslt.data.StoredRow;

/** {@link Persistence} sobre o Room. */
public class RoomPersistence implements Persistence {

    private final ContasDatabase db;
    private final RecordDao dao;

    public RoomPersistence(ContasDatabase db) {
        this.db = db;
        this.dao = db.dao();
    }

    @Override
    public List<StoredRow> loadAll() {
        List<StoredRow> out = new ArrayList<>();
        for (RecordEntity r : dao.all()) {
            out.add(new StoredRow(r.id, r.entity, r.data, r.deleted, r.updatedAt));
        }
        return out;
    }

    @Override
    public void write(List<StoredRow> batch) {
        db.runInTransaction(() -> {
            dao.putRecords(toRecords(batch));
            dao.putOutbox(toOutbox(batch));
        });
    }

    @Override
    public void applyRemote(List<StoredRow> accepted) {
        db.runInTransaction(() -> {
            dao.putRecords(toRecords(accepted));
            Map<String, Long> remote = new HashMap<>();
            for (StoredRow r : accepted) {
                remote.put(r.id, r.updatedAt);
            }
            List<String> superseded = new ArrayList<>();
            for (List<String> chunk : chunks(new ArrayList<>(remote.keySet()))) {
                for (OutboxEntity o : dao.outboxByIds(chunk)) {
                    if (o.updatedAt <= remote.get(o.id)) {
                        superseded.add(o.id);
                    }
                }
            }
            for (List<String> chunk : chunks(superseded)) {
                dao.deleteOutbox(chunk);
            }
        });
    }

    @Override
    public List<StoredRow> pendingBatch(int limit) {
        List<StoredRow> out = new ArrayList<>();
        for (OutboxEntity o : dao.pending(limit)) {
            out.add(new StoredRow(o.id, o.entity, o.data, o.deleted, o.updatedAt));
        }
        return out;
    }

    @Override
    public void acknowledge(List<StoredRow> sent) {
        db.runInTransaction(() -> {
            Map<String, Long> sentAt = new HashMap<>();
            for (StoredRow s : sent) {
                sentAt.put(s.id, s.updatedAt);
            }
            List<String> done = new ArrayList<>();
            for (List<String> chunk : chunks(new ArrayList<>(sentAt.keySet()))) {
                for (OutboxEntity o : dao.outboxByIds(chunk)) {
                    if (o.updatedAt == sentAt.get(o.id)) {
                        done.add(o.id);
                    }
                }
            }
            for (List<String> chunk : chunks(done)) {
                dao.deleteOutbox(chunk);
            }
        });
    }

    @Override
    public int pendingCount() {
        return dao.pendingCount();
    }

    @Override
    public String getMeta(String key) {
        return dao.meta(key);
    }

    @Override
    public void setMeta(String key, String value) {
        MetaEntity m = new MetaEntity();
        m.key = key;
        m.value = value;
        dao.putMeta(m);
    }

    @Override
    public void close() {
        db.close();
    }

    private static List<RecordEntity> toRecords(List<StoredRow> rows) {
        List<RecordEntity> out = new ArrayList<>(rows.size());
        for (StoredRow r : rows) {
            RecordEntity e = new RecordEntity();
            e.id = r.id;
            e.entity = r.entity;
            e.data = r.data;
            e.deleted = r.deleted;
            e.updatedAt = r.updatedAt;
            out.add(e);
        }
        return out;
    }

    private static List<OutboxEntity> toOutbox(List<StoredRow> rows) {
        List<OutboxEntity> out = new ArrayList<>(rows.size());
        for (StoredRow r : rows) {
            OutboxEntity e = new OutboxEntity();
            e.id = r.id;
            e.entity = r.entity;
            e.data = r.data;
            e.deleted = r.deleted;
            e.updatedAt = r.updatedAt;
            out.add(e);
        }
        return out;
    }

    /** O SQLite limita o número de parâmetros por consulta. */
    private static List<List<String>> chunks(List<String> ids) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 500) {
            out.add(ids.subList(i, Math.min(ids.size(), i + 500)));
        }
        return out;
    }
}
