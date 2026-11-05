package br.com.contaslt.ui;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.snackbar.Snackbar;

import br.com.contaslt.data.ApiException;
import br.com.contaslt.services.Ctx;

/**
 * Ponte entre as telas e os serviços (o papel de frontend/src/api/index.ts): roda a regra fora da thread principal,
 * espera o banco gravar e entrega o resultado na thread principal, só se a tela ainda estiver viva.
 */
public final class Repo {

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "contaslt-work");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Repo() {
    }

    /** Executa {@code work}; erro vira Snackbar em {@code anchor} (se dado) ou é entregue a {@code onError}. */
    public static <T> void run(LifecycleOwner owner, Callable<T> work, Consumer<T> onOk, Consumer<ApiException> onError) {
        WORK.execute(() -> {
            T result;
            ApiException err = null;
            try {
                result = work.call();
                if (Ctx.isTestStore() || hasStore()) {
                    Ctx.db().flush();
                }
            } catch (ApiException e) {
                result = null;
                err = e;
            } catch (Exception e) {
                Log.e("ContasLT", "falha", e);
                result = null;
                err = new ApiException(-1, e.getMessage() == null ? "Algo deu errado. Tente de novo." : e.getMessage());
            }
            T r = result;
            ApiException e2 = err;
            MAIN.post(() -> {
                if (owner != null && owner.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) {
                    return;
                }
                if (e2 != null) {
                    if (onError != null) {
                        onError.accept(e2);
                    }
                } else if (onOk != null) {
                    onOk.accept(r);
                }
            });
        });
    }

    public static <T> void run(LifecycleOwner owner, View anchor, Callable<T> work, Consumer<T> onOk) {
        run(owner, work, onOk, e -> {
            if (anchor != null) {
                Snackbar.make(anchor, e.getMessage(), Snackbar.LENGTH_LONG).show();
            }
        });
    }

    /** Só para ações sem resultado. */
    public static void runAction(LifecycleOwner owner, View anchor, ThrowingRunnable work, Runnable onOk) {
        run(owner, anchor, () -> {
            work.run();
            return Boolean.TRUE;
        }, ok -> {
            if (onOk != null) {
                onOk.run();
            }
        });
    }

    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static boolean hasStore() {
        try {
            Ctx.db();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
