package br.com.contaslt.notify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Botões "Confirmar"/"Descartar" da notificação de sugestão. */
public class InboxActionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String id = intent.getStringExtra(NotificationProcessor.EXTRA_ID);
        String action = intent.getAction();
        if (id == null || action == null) {
            return;
        }
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                NotificationProcessor.handleAction(context.getApplicationContext(), action, id);
            } finally {
                pending.finish();
            }
        }, "contaslt-inbox").start();
    }
}
