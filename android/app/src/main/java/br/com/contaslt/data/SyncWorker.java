package br.com.contaslt.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import br.com.contaslt.ContasApp;
import br.com.contaslt.dto.User;

/** Sincronização periódica, também com o app fechado (o WorkManager acorda o processo). */
public class SyncWorker extends Worker {

    static final String NAME = "contaslt-sync";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        ContasApp app = (ContasApp) getApplicationContext();
        Session session = app.container().session;
        User user = app.container().prefs.user();
        if (user == null || app.container().prefs.token() == null) {
            return Result.success();
        }
        session.open(user.id);
        session.syncNow();
        return Result.success();
    }
}
