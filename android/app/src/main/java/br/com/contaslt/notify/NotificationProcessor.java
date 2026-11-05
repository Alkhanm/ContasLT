package br.com.contaslt.notify;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import br.com.contaslt.AppContainer;
import br.com.contaslt.ContasApp;
import br.com.contaslt.R;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.domain.Ids;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.dto.User;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.MainActivity;

/**
 * Recebe o texto de uma notificação de app de banco, guarda na caixa "A confirmar" e avisa com uma notificação
 * própria (botões Confirmar e Descartar). Também confirma/descarta a partir desses botões.
 */
public final class NotificationProcessor {

    private static final String TAG = "ContasLT";
    static final String CHANNEL = "inbox";
    static final String ACTION_CONFIRM = "br.com.contaslt.inbox.CONFIRM";
    static final String ACTION_DISCARD = "br.com.contaslt.inbox.DISCARD";
    static final String EXTRA_ID = "id";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    private NotificationProcessor() {
    }

    public static InboxStore store(Context context) {
        User u = app(context).prefs.user();
        return u == null ? null : new InboxStore(context, u.id);
    }

    /** Chamado pelo listener para cada notificação postada (fora da thread principal). */
    public static void handle(Context context, String pkg, String title, String text, long postedAt) {
        WORK.execute(() -> {
            try {
                InboxStore store = store(context);
                if (store == null || pkg.equals(context.getPackageName()) || !store.isMonitored(pkg)) {
                    return;
                }
                InboxItem item = toItem(context, pkg, title, text, postedAt);
                if (store.add(item) && InboxItem.PENDING.equals(item.status) && store.notifyEnabled()) {
                    notifySuggestion(context, item);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "notificação não processada", e);
            }
        });
    }

    /** Monta o item (lido ou não reconhecido). Também usado pelo "Testar" da tela de notificações. */
    public static InboxItem toItem(Context context, String pkg, String title, String text, long postedAt) {
        InboxItem item = new InboxItem();
        item.id = UUID.randomUUID().toString();
        item.packageName = pkg;
        item.appLabel = appLabel(context, pkg);
        item.title = title;
        item.text = text;
        item.postedAt = postedAt;
        item.hash = hex(Ids.sha1((pkg + "\n" + title + "\n" + text).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String posted = java.time.Instant.ofEpochMilli(postedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
        BankParser.Parsed p = BankParser.parse(title, text, posted);
        if (p == null) {
            item.status = InboxItem.UNRECOGNIZED;
            return item;
        }
        item.status = InboxItem.PENDING;
        item.type = p.type;
        item.amount = p.amount;
        item.kind = p.kind.name();
        item.counterpart = p.counterpart;
        item.cardLast4 = p.cardLast4;
        item.date = p.date;
        item.description = p.description();
        return item;
    }

    public static String appLabel(Context context, String pkg) {
        String known = BankApps.label(pkg);
        if (known != null) {
            return known;
        }
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    // ---------- Notificação própria ----------

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Lançamentos a confirmar", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Movimentos lidos das notificações dos bancos, para confirmar com um toque");
            context.getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    static void notifySuggestion(Context context, InboxItem item) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureChannel(context);
        int code = item.id.hashCode();
        String what = ("INCOME".equals(item.type) ? "Receita de " : "Despesa de ") + Format.money(item.amount);
        Intent open = new Intent(context, MainActivity.class).putExtra("openInbox", true)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        NotificationCompat.Builder nb = new NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_wallet)
            .setColor(ContextCompat.getColor(context, R.color.primary))
            .setContentTitle(what + " · " + item.description)
            .setContentText((item.appLabel == null ? "" : item.appLabel + " · ") + Format.dateShort(item.date) + " · toque para revisar")
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, code, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(0, "Confirmar", action(context, ACTION_CONFIRM, item.id, code + 1))
            .addAction(0, "Descartar", action(context, ACTION_DISCARD, item.id, code + 2));
        NotificationManagerCompat.from(context).notify(item.id, 1, nb.build());
    }

    private static PendingIntent action(Context context, String action, String id, int code) {
        Intent i = new Intent(context, InboxActionReceiver.class).setAction(action).putExtra(EXTRA_ID, id);
        return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Botões da notificação. Bloqueante: chame numa thread de trabalho. */
    static void handleAction(Context context, String action, String id) {
        InboxStore store = store(context);
        if (store == null) {
            return;
        }
        NotificationManagerCompat.from(context).cancel(id, 1);
        InboxItem item = store.get(id);
        if (item == null || !InboxItem.PENDING.equals(item.status)) {
            return;
        }
        if (ACTION_DISCARD.equals(action)) {
            store.setStatus(id, InboxItem.DISCARDED, null);
            return;
        }
        AppContainer app = app(context);
        app.auth.ensureSession();
        if (!app.session.isOpen()) {
            return;
        }
        InboxService.Suggestion s = InboxService.suggest(item, store.defaultAccount(item.packageName), store.defaultCard(item.packageName));
        try {
            Transaction t = InboxService.confirm(s);
            app.session.store().flush();
            store.setStatus(id, InboxItem.CONFIRMED, t.id);
            app.session.scheduleSync(0);
        } catch (RuntimeException e) {
            // Falta completar (ex.: nenhuma conta): fica na caixa para abrir no app.
            Log.w(TAG, "não foi possível confirmar pela notificação", e);
        }
    }

    private static AppContainer app(Context context) {
        return ((ContasApp) context.getApplicationContext()).container();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format(Locale.ROOT, "%02x", x));
        }
        return sb.toString();
    }

    /** Data de hoje (para o "Testar"). */
    public static String today() {
        return Dates.today();
    }
}
