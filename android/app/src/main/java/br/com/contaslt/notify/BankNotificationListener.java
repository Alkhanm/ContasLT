package br.com.contaslt.notify;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Escuta as notificações do aparelho (com o "Acesso a notificações" liberado pelo usuário) e passa ao
 * {@link NotificationProcessor} só o texto das que vêm de apps de banco monitorados. Nada sai do aparelho.
 */
public class BankNotificationListener extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 || sbn.isOngoing()) {
            return;
        }
        Bundle extras = n.extras;
        if (extras == null) {
            return;
        }
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence text = big != null ? big : extras.getCharSequence(Notification.EXTRA_TEXT);
        if (text == null && title == null) {
            return;
        }
        NotificationProcessor.handle(getApplicationContext(), sbn.getPackageName(),
            title == null ? null : title.toString(), text == null ? null : text.toString(), sbn.getPostTime());
    }
}
