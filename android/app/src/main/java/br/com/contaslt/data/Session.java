package br.com.contaslt.data;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.util.Log;

import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import br.com.contaslt.data.db.ContasDatabase;
import br.com.contaslt.data.db.RoomPersistence;
import br.com.contaslt.services.Ctx;

/**
 * Sessão local do usuário logado (porte de frontend/src/data/session.ts): o banco do aparelho, a memória e a
 * sincronização em segundo plano. Sincroniza ao abrir, ao voltar para o app, ao reconectar, logo depois de gravar
 * (1,5 s) e a cada 15 minutos (WorkManager, também com o app fechado).
 */
public class Session {

    private static final String TAG = "ContasLT";

    public enum Status { IDLE, SYNCING, OFFLINE, ERROR }

    /** Estado da sincronização para a interface. */
    public static final class SyncState {
        public final Status status;
        /** Gravações deste aparelho que ainda não chegaram ao servidor. */
        public final int pending;
        public final Long lastSync;
        public final String error;
        /** Já houve uma sincronização completa neste aparelho. */
        public final boolean initialDone;

        SyncState(Status status, int pending, Long lastSync, String error, boolean initialDone) {
            this.status = status;
            this.pending = pending;
            this.lastSync = lastSync;
            this.error = error;
            this.initialDone = initialDone;
        }
    }

    public final MutableLiveData<SyncState> state = new MutableLiveData<>(new SyncState(Status.IDLE, 0, null, null, false));
    /** Cresce a cada mudança nos dados (local ou vinda do servidor). As telas observam para recalcular. */
    public final MutableLiveData<Long> dataVersion = new MutableLiveData<>(0L);

    private final Context context;
    private final Api api;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService syncThread = Executors.newSingleThreadExecutor();
    private final List<Runnable> afterSync = new CopyOnWriteArrayList<>();
    private final List<java.util.function.Consumer<LocalStore.Origin>> changeListeners = new CopyOnWriteArrayList<>();

    private volatile Long userId;
    private volatile LocalStore store;
    private Runnable unsubscribe;
    private ScheduledFuture<?> pendingSync;
    private volatile boolean running;
    private volatile boolean again;

    public Session(Context context, Api api) {
        this.context = context.getApplicationContext();
        this.api = api;
        ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(LifecycleOwner owner) {
                scheduleSync(0);
            }
        });
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    scheduleSync(500);
                }
            });
        }
    }

    public boolean isOpen() {
        return store != null;
    }

    public Long userId() {
        return userId;
    }

    public LocalStore store() {
        LocalStore s = store;
        if (s == null) {
            throw new IllegalStateException("Sessão local não aberta");
        }
        return s;
    }

    /** Abre o banco do usuário (bloqueante: chame fora da thread principal). */
    public synchronized LocalStore open(long id) {
        if (store != null && userId != null && userId == id) {
            return store;
        }
        close();
        LocalStore s = new LocalStore(new RoomPersistence(ContasDatabase.open(context, id)));
        s.load();
        boolean initialDone = "true".equals(s.getMeta(SyncEngine.INITIAL_DONE_KEY));
        Ctx.setStore(s);
        Ctx.setInitialSyncDone(initialDone);
        unsubscribe = s.subscribe(origin -> {
            dataVersion.postValue(s.version());
            for (java.util.function.Consumer<LocalStore.Origin> l : changeListeners) {
                l.accept(origin);
            }
            if (origin == LocalStore.Origin.LOCAL) {
                scheduleSync(1500);
            }
        });
        userId = id;
        store = s;
        publish(Status.IDLE, s.pendingCount(), null, null, initialDone);
        dataVersion.postValue(s.version());
        schedulePeriodic();
        scheduleSync(0);
        return s;
    }

    public synchronized void close() {
        if (pendingSync != null) {
            pendingSync.cancel(false);
        }
        if (unsubscribe != null) {
            unsubscribe.run();
        }
        if (store != null) {
            store.close();
        }
        store = null;
        userId = null;
        Ctx.setStore(null);
        Ctx.setInitialSyncDone(false);
        WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.NAME);
        publish(Status.IDLE, 0, null, null, false);
    }

    /** Logout de verdade: apaga o banco deste usuário do aparelho. */
    public synchronized void wipe(long id) {
        if (userId != null && userId == id) {
            close();
        }
        context.deleteDatabase(ContasDatabase.fileName(id));
    }

    public Runnable onDataChange(java.util.function.Consumer<LocalStore.Origin> listener) {
        changeListeners.add(listener);
        return () -> changeListeners.remove(listener);
    }

    /** Roda depois de cada sincronização bem-sucedida (ex.: leituras de comprovante que terminaram). */
    public void onAfterSync(Runnable hook) {
        afterSync.add(hook);
    }

    public synchronized void scheduleSync(long delayMs) {
        if (store == null) {
            return;
        }
        if (pendingSync != null) {
            pendingSync.cancel(false);
        }
        pendingSync = scheduler.schedule(() -> syncThread.execute(this::syncNow), delayMs, TimeUnit.MILLISECONDS);
    }

    /** Sincroniza agora (bloqueante). Se já há uma rodada, marca para repetir no fim dela. */
    public void syncNow() {
        if (running) {
            again = true;
            return;
        }
        running = true;
        try {
            do {
                again = false;
                runOnce();
            } while (again && store != null);
        } finally {
            running = false;
        }
    }

    private void runOnce() {
        LocalStore s = store;
        if (s == null) {
            return;
        }
        SyncState cur = state.getValue();
        boolean initialDone = cur != null && cur.initialDone;
        publish(Status.SYNCING, cur == null ? 0 : cur.pending, cur == null ? null : cur.lastSync, null, initialDone);
        try {
            SyncEngine.syncOnce(s, api);
            if (s != store) {
                return;
            }
            Ctx.setInitialSyncDone(true);
            for (Runnable hook : afterSync) {
                try {
                    hook.run();
                } catch (RuntimeException e) {
                    Log.w(TAG, "pós-sincronização falhou", e);
                }
            }
            publish(Status.IDLE, s.pendingCount(), System.currentTimeMillis(), null, true);
        } catch (ApiException e) {
            if (s != store) {
                return;
            }
            publish(e.status == 0 ? Status.OFFLINE : Status.ERROR, s.pendingCount(), cur == null ? null : cur.lastSync,
                e.getMessage(), initialDone);
            if (e.status != 401) {
                scheduleSync(30_000);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "sincronização falhou", e);
            if (s == store) {
                publish(Status.ERROR, s.pendingCount(), null, e.getMessage(), initialDone);
                scheduleSync(30_000);
            }
        }
    }

    private void publish(Status status, int pending, Long lastSync, String error, boolean initialDone) {
        state.postValue(new SyncState(status, pending, lastSync, error, initialDone));
    }

    private void schedulePeriodic() {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(SyncWorker.class, 15, TimeUnit.MINUTES)
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(SyncWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, req);
    }
}
