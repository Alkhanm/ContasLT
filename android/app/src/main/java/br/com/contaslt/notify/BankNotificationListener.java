package br.com.contaslt.notify;

import java.util.ArrayList;
import java.util.List;

import android.app.Notification;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Escuta as notificações do aparelho (com o "Acesso a notificações" liberado pelo usuário) e passa ao
 * {@link NotificationProcessor} o texto das que vêm de apps de banco monitorados. Junta todos os campos de texto da
 * notificação (título, texto, texto expandido, linhas, subtexto, resumo, mensagens), porque cada banco põe o
 * favorecido, a conta ou o horário num lugar diferente.
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
        CharSequence big = extras.getCharSequence(Notification.EXTRA_TITLE_BIG);
        CharSequence title = big != null ? big : extras.getCharSequence(Notification.EXTRA_TITLE);
        String text = collectText(extras, title == null ? null : title.toString());
        if (text == null && title == null) {
            return;
        }
        NotificationProcessor.handle(getApplicationContext(), sbn.getPackageName(), sbn.getKey(),
            title == null ? null : title.toString(), text, sbn.getPostTime());
    }

    /** Texto expandido, texto, linhas (InboxStyle), mensagens (MessagingStyle), subtexto, resumo e info, sem repetir. */
    static String collectText(Bundle extras, String title) {
        List<String> parts = new ArrayList<>();
        add(parts, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        add(parts, extras.getCharSequence(Notification.EXTRA_TEXT));
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) {
            for (CharSequence l : lines) {
                add(parts, l);
            }
        }
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null) {
            for (Parcelable p : messages) {
                if (p instanceof Bundle b) {
                    add(parts, b.getCharSequence("text"));
                }
            }
        }
        add(parts, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        add(parts, extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
        add(parts, extras.getCharSequence(Notification.EXTRA_INFO_TEXT));
        return NotificationText.join(title, parts);
    }

    private static void add(List<String> parts, CharSequence cs) {
        if (cs != null) {
            parts.add(cs.toString());
        }
    }
}
