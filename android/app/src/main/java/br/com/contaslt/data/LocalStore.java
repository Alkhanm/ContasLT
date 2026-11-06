package br.com.contaslt.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.data.schema.Rec;

/**
 * Todos os dados do usuário em memória, espelhados na persistência (Room). Porte de frontend/src/data/store.ts:
 * leituras síncronas sobre mapas em memória; gravações atualizam a memória na hora e vão para o banco (registro +
 * outbox) numa transação, numa thread própria. {@link #flush()} espera essa gravação.
 *
 * <p>Conflitos: um registro do servidor só substitui o local se o updatedAt dele for maior ou igual.
 */
public class LocalStore {

    public enum Origin { LOCAL, REMOTE }

    /** Registro em memória: o objeto já lido do JSON. */
    public static final class Stored {
        public final String id;
        public final Entity<?> entity;
        public final Rec data;
        public final boolean deleted;
        public final long updatedAt;

        Stored(String id, Entity<?> entity, Rec data, boolean deleted, long updatedAt) {
            this.id = id;
            this.entity = entity;
            this.data = data;
            this.deleted = deleted;
            this.updatedAt = updatedAt;
        }

        StoredRow toRow() {
            return new StoredRow(id, entity.name, data == null ? null : Json.GSON.toJson(data), deleted, updatedAt);
        }
    }

    private final Persistence persistence;
    private final ExecutorService writer;
    private final Map<Entity<?>, Map<String, Stored>> maps = new HashMap<>();
    private final List<Consumer<Origin>> listeners = new CopyOnWriteArrayList<>();
    private final List<StoredRow> queue = new ArrayList<>();
    private long clock;
    private volatile long version;

    public LocalStore(Persistence persistence) {
        this.persistence = persistence;
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "contaslt-store");
            t.setDaemon(true);
            return t;
        });
        for (Entity<?> e : Entity.ALL) {
            maps.put(e, new LinkedHashMap<>());
        }
    }

    /** Carrega tudo da persistência (abertura do app). Fora da thread principal. */
    public synchronized void load() {
        for (Map<String, Stored> m : maps.values()) {
            m.clear();
        }
        for (StoredRow r : persistence.loadAll()) {
            Stored s = fromRow(r);
            if (s == null) {
                continue;
            }
            maps.get(s.entity).put(s.id, s);
            clock = Math.max(clock, r.updatedAt);
        }
        version++;
    }

    // ---------- Leitura ----------

    @SuppressWarnings("unchecked")
    public synchronized <T extends Rec> List<T> list(Entity<T> entity) {
        List<T> out = new ArrayList<>();
        for (Stored s : maps.get(entity).values()) {
            if (!s.deleted && s.data != null) {
                out.add((T) s.data);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public synchronized <T extends Rec> T get(Entity<T> entity, String id) {
        if (id == null) {
            return null;
        }
        Stored s = maps.get(entity).get(id);
        return s != null && !s.deleted ? (T) s.data : null;
    }

    /** Existe registro com este id, mesmo excluído. */
    public synchronized boolean known(Entity<?> entity, String id) {
        return maps.get(entity).containsKey(id);
    }

    public synchronized boolean isEmpty() {
        for (Map<String, Stored> m : maps.values()) {
            for (Stored s : m.values()) {
                if (!s.deleted) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Cresce a cada mudança nos dados (local ou remota). */
    public long version() {
        return version;
    }

    // ---------- Escrita ----------

    public <T extends Rec> void put(Entity<T> entity, T data) {
        put(entity, data, false);
    }

    /**
     * Grava (cria ou substitui). {@code generated}: vencimento criado pelo gerador de recorrentes, com relógio baixo
     * para nunca vencer uma edição real; se o id já existe (inclusive excluído), vai com o relógio atual.
     */
    public <T extends Rec> void put(Entity<T> entity, T data, boolean generated) {
        synchronized (this) {
            Stored prev = maps.get(entity).get(data.id);
            long updatedAt = generated && prev == null ? Entity.GENERATED_AT : tick();
            write(new Stored(data.id, entity, data.copy(), false, updatedAt));
        }
        notifyListeners(Origin.LOCAL);
    }

    public void remove(Entity<?> entity, String id) {
        synchronized (this) {
            Stored prev = maps.get(entity).get(id);
            if (prev == null || prev.deleted) {
                return;
            }
            write(new Stored(id, entity, null, true, tick()));
        }
        notifyListeners(Origin.LOCAL);
    }

    /** Espera a persistência terminar de gravar o que já está na memória. */
    public void flush() {
        Future<?> f;
        synchronized (this) {
            f = scheduleWrite();
        }
        await(f);
    }

    private void write(Stored s) {
        maps.get(s.entity).put(s.id, s);
        queue.add(s.toRow());
        version++;
        scheduleWrite();
    }

    private Future<?> scheduleWrite() {
        return writer.submit(() -> {
            List<StoredRow> batch;
            synchronized (LocalStore.this) {
                if (queue.isEmpty()) {
                    return;
                }
                batch = new ArrayList<>(queue);
                queue.clear();
            }
            try {
                persistence.write(batch);
            } catch (RuntimeException e) {
                // A memória continua certa; o lote volta para a fila e é tentado na próxima gravação.
                synchronized (LocalStore.this) {
                    queue.addAll(0, batch);
                }
                throw e;
            }
        });
    }

    private long tick() {
        clock = Math.max(System.currentTimeMillis(), clock + 1);
        return clock;
    }

    // ---------- Sincronização ----------

    /** Aplica registros do servidor (vence o mais novo; empate aceita o remoto). Devolve quantos mudaram algo. */
    public int applyRemote(List<StoredRow> records) {
        if (records.isEmpty()) {
            return 0;
        }
        flush();
        List<StoredRow> accepted = new ArrayList<>();
        synchronized (this) {
            for (StoredRow r : records) {
                Entity<?> e = Entity.byName(r.entity);
                if (e == null) {
                    continue;
                }
                Stored local = maps.get(e).get(r.id);
                if (local != null && local.updatedAt > r.updatedAt) {
                    continue;
                }
                accepted.add(r);
            }
        }
        await(writer.submit(() -> persistence.applyRemote(accepted)));
        int changed = 0;
        synchronized (this) {
            long now = System.currentTimeMillis();
            for (StoredRow r : accepted) {
                Stored s = fromRow(r);
                if (s == null) {
                    continue;
                }
                Stored local = maps.get(s.entity).get(s.id);
                if (local != null && local.updatedAt == s.updatedAt && local.deleted == s.deleted) {
                    continue;
                }
                maps.get(s.entity).put(s.id, s);
                if (r.updatedAt > clock && r.updatedAt < now + 86_400_000L) {
                    clock = r.updatedAt;
                }
                changed++;
            }
            if (changed > 0) {
                version++;
            }
        }
        if (changed > 0) {
            notifyListeners(Origin.REMOTE);
        }
        return changed;
    }

    public List<StoredRow> pendingBatch(int limit) {
        flush();
        return call(() -> persistence.pendingBatch(limit));
    }

    public void acknowledge(List<StoredRow> sent) {
        await(writer.submit(() -> persistence.acknowledge(sent)));
    }

    public int pendingCount() {
        flush();
        return call(persistence::pendingCount);
    }

    public String getMeta(String key) {
        return call(() -> persistence.getMeta(key));
    }

    public void setMeta(String key, String value) {
        await(writer.submit(() -> persistence.setMeta(key, value)));
    }

    public void close() {
        try {
            flush();
        } finally {
            writer.submit(persistence::close);
            writer.shutdown();
        }
    }

    // ---------- Avisos ----------

    public Runnable subscribe(Consumer<Origin> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void notifyListeners(Origin origin) {
        for (Consumer<Origin> l : listeners) {
            l.accept(origin);
        }
    }

    // ---------- Auxiliares ----------

    private static Stored fromRow(StoredRow r) {
        Entity<?> e = Entity.byName(r.entity);
        if (e == null) {
            return null;
        }
        Rec data = r.deleted || r.data == null ? null : Json.GSON.fromJson(r.data, e.type);
        return new Stored(r.id, e, data, r.deleted, r.updatedAt);
    }

    private <V> V call(java.util.concurrent.Callable<V> c) {
        return await(writer.submit(c));
    }

    private static <V> V await(Future<V> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(cause);
        }
    }

    /** Executor que roda na hora (testes). */
    public static Executor direct() {
        return Runnable::run;
    }
}
