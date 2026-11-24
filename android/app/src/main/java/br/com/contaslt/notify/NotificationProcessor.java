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
    public static void handle(Context context, String pkg, String key, String title, String text, long postedAt) {
        WORK.execute(() -> {
            try {
                if (pkg.equals(context.getPackageName())) {
                    return;
                }
                InboxStore store = store(context);
                if (store == null || !store.isMonitored(pkg)) {
                    return;
                }
                openSession(context);
                store.migrateLocal();
                InboxItem item = toItem(context, pkg, title, text, postedAt);
                item.notificationKey = key;
                item.defaultAccountId = store.defaultAccount(pkg);
                item.defaultCardId = store.defaultCard(pkg);
                // O banco atualizou a mesma notificação (ex.: "Pix enviado" e depois o nome do favorecido).
                InboxItem prev = store.findByKey(key, postedAt);
                boolean saved;
                if (prev != null && prev.hash.equals(item.hash)) {
                    return;
                }
                if (prev != null && sameMovement(prev, item)) {
                    if (!InboxItem.PENDING.equals(prev.status) && !InboxItem.UNRECOGNIZED.equals(prev.status)) {
                        return;
                    }
                    item.id = prev.id;
                    item.createdAt = prev.createdAt;
                    item.postedAt = prev.postedAt;
                    store.replace(item);
                    saved = true;
                } else {
                    saved = store.add(item);
                }
                if (saved && InboxItem.PENDING.equals(item.status) && store.notifyEnabled()) {
                    notifySuggestion(context, item);
                }
                if (app(context).session.isOpen()) {
                    app(context).session.store().flush();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "notificação não processada", e);
            }
        });
    }

    /**
     * A notificação nova (mesma chave, pouco depois) é a mesma movimentação reescrita pelo banco? Alguns apps usam
     * uma chave só para todas as notificações, então só junta com o mesmo valor e tipo, ou quando o texto novo
     * contém o anterior (que não tinha sido reconhecido).
     */
    static boolean sameMovement(InboxItem prev, InboxItem next) {
        boolean prevOk = !InboxItem.UNRECOGNIZED.equals(prev.status);
        boolean nextOk = !InboxItem.UNRECOGNIZED.equals(next.status);
        if (prevOk && nextOk) {
            return prev.amount == next.amount && java.util.Objects.equals(prev.type, next.type);
        }
        if (!prevOk) {
            String a = BankParser.fold(((prev.title == null ? "" : prev.title) + " " + (prev.text == null ? "" : prev.text)).replaceAll("\\s+", " ").trim());
            String b = BankParser.fold(((next.title == null ? "" : next.title) + " " + (next.text == null ? "" : next.text)).replaceAll("\\s+", " ").trim());
            return !a.isEmpty() && b.contains(a);
        }
        return false;
    }

    /** Abre os dados locais do usuário (o processo pode ter sido iniciado só pelo listener). */
    private static void openSession(Context context) {
        try {
            app(context).auth.ensureSession();
        } catch (RuntimeException e) {
            Log.w(TAG, "sessão local não abriu; a notificação fica no aparelho", e);
        }
    }

    /**
     * Depois de mudanças nos dados (inclusive vindas de outro aparelho): atualiza a contagem e tira da barra os
     * avisos das sugestões que já foram confirmadas ou descartadas, no app ou no PWA.
     */
    public static void onDataChanged(Context context) {
        WORK.execute(() -> {
            try {
                InboxStore store = store(context);
                if (store == null) {
                    return;
                }
                store.migrateLocal();
                store.refreshCount();
                long since = System.currentTimeMillis() - 7 * 86_400_000L;
                NotificationManagerCompat nm = NotificationManagerCompat.from(context);
                for (InboxItem i : store.recognized()) {
                    if (!InboxItem.PENDING.equals(i.status) && i.postedAt > since) {
                        nm.cancel(i.id, 1);
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "caixa não atualizada", e);
            }
        });
    }

    /** Monta o item (lido ou não reconhecido). Também usado pelo "Testar" da tela de notificações. */
    public static InboxItem toItem(Context context, String pkg, String title, String text, long postedAt) {
        InboxItem item = new InboxItem();
        item.id = UUID.randomUUID().toString();
        item.createdAt = br.com.contaslt.domain.Dates.nowIso();
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
            .setOnlyAlertOnce(true)
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
        AppContainer app = app(context);
        app.auth.ensureSession();
        if (!app.session.isOpen()) {
            return;
        }
        store.migrateLocal();
        InboxItem item = store.get(id);
        if (item == null || !InboxItem.PENDING.equals(item.status)) {
            return;
        }
        if (ACTION_DISCARD.equals(action)) {
            store.setStatus(id, InboxItem.DISCARDED, null);
            app.session.store().flush();
            app.session.scheduleSync(0);
            return;
        }
        InboxService.Suggestion s = InboxService.suggest(item, store);
        try {
            Transaction t = InboxService.confirm(s);
            store.setStatus(id, InboxItem.CONFIRMED, t.id);
            app.session.store().flush();
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
