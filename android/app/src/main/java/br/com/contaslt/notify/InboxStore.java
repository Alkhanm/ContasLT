package br.com.contaslt.notify;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.lifecycle.MutableLiveData;

import com.google.gson.reflect.TypeToken;

import br.com.contaslt.data.Json;
import br.com.contaslt.data.LocalStore;
import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.services.Ctx;

/**
 * Caixa "A confirmar". As notificações reconhecidas são registros {@code bankNotification} nos dados do usuário
 * (sincronizam: dá para confirmar no PWA o que chegou no celular). As não reconhecidas e as preferências (apps
 * monitorados, conta padrão de cada um, aviso próprio) ficam só no aparelho, no SharedPreferences.
 *
 * <p>Uma notificação que chega com a sessão local fechada (ex.: processo iniciado só pelo listener e sem login), ou
 * antes de o usuário aceitar o aviso que diz que a caixa sincroniza ({@link #syncConsent()}), fica na lista do
 * aparelho como PENDING e vai para os dados do usuário em {@link #migrateLocal()}.
 */
public final class InboxStore {

    private static final int MAX_LOCAL = 200;
    private static final long KEEP_DONE_MS = 30L * 86_400_000L;
    private static final long DUP_WINDOW_MS = 3 * 86_400_000L;
    private static final Type LIST = new TypeToken<List<InboxItem>>() { }.getType();

    /** Quantas sugestões aguardam confirmação (badge). */
    public static final MutableLiveData<Integer> pendingCount = new MutableLiveData<>(0);

    private final SharedPreferences sp;

    public InboxStore(Context context, long userId) {
        sp = context.getSharedPreferences("contaslt-inbox-u" + userId, Context.MODE_PRIVATE);
    }

    private static LocalStore db() {
        try {
            return Ctx.db();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /** Os dados do usuário, para gravar a caixa sincronizada; null sem sessão ou sem o consentimento. */
    private LocalStore syncDb() {
        return syncConsent() ? db() : null;
    }

    /**
     * O usuário aceitou o aviso que diz que a caixa sincroniza com a conta (versão 2 do aviso). Antes disso, como
     * na versão anterior do app, as sugestões ficam só no aparelho.
     */
    public boolean syncConsent() {
        return sp.getBoolean("syncConsent", false);
    }

    public void setSyncConsent(boolean on) {
        sp.edit().putBoolean("syncConsent", on).putBoolean("syncAsked", true).apply();
    }

    /** Já perguntou a quem tinha liberado o acesso antes da caixa sincronizar. */
    public boolean syncAsked() {
        return sp.getBoolean("syncAsked", false);
    }

    public void setSyncAsked() {
        sp.edit().putBoolean("syncAsked", true).apply();
    }

    // ---------- Itens ----------

    /** Sugestões aguardando confirmação, da mais nova para a mais antiga. */
    public synchronized List<InboxItem> pending() {
        List<InboxItem> out = new ArrayList<>();
        LocalStore db = db();
        if (db != null) {
            for (InboxItem i : db.list(Entity.BANK_NOTIFICATION)) {
                if (InboxItem.PENDING.equals(i.status)) {
                    out.add(i);
                }
            }
        }
        for (InboxItem i : local()) {
            if (InboxItem.PENDING.equals(i.status)) {
                out.add(i);
            }
        }
        out.sort((a, b) -> Long.compare(b.postedAt, a.postedAt));
        return out;
    }

    /** Todas as reconhecidas guardadas (qualquer status). */
    public synchronized List<InboxItem> recognized() {
        LocalStore db = db();
        return db == null ? new ArrayList<>() : db.list(Entity.BANK_NOTIFICATION);
    }

    /** Não reconhecidas (só neste aparelho), da mais nova para a mais antiga. */
    public synchronized List<InboxItem> unrecognized() {
        List<InboxItem> out = new ArrayList<>();
        for (InboxItem i : local()) {
            if (InboxItem.UNRECOGNIZED.equals(i.status)) {
                out.add(i);
            }
        }
        return out;
    }

    public synchronized InboxItem get(String id) {
        LocalStore db = db();
        InboxItem i = db == null ? null : db.get(Entity.BANK_NOTIFICATION, id);
        if (i != null) {
            return i;
        }
        for (InboxItem l : local()) {
            if (l.id.equals(id)) {
                return l;
            }
        }
        return null;
    }

    /** Guarda a notificação; devolve false se já havia uma igual (mesmo hash) nos últimos 3 dias. */
    public synchronized boolean add(InboxItem item) {
        LocalStore all = db();
        if (all != null) {
            for (InboxItem i : all.list(Entity.BANK_NOTIFICATION)) {
                if (same(i, item)) {
                    return false;
                }
            }
        }
        List<InboxItem> list = local();
        for (InboxItem i : list) {
            if (same(i, item)) {
                return false;
            }
        }
        if (item.createdAt == null) {
            item.createdAt = Dates.nowIso();
        }
        LocalStore db = syncDb();
        if (InboxItem.UNRECOGNIZED.equals(item.status) || db == null) {
            list.add(0, item);
            saveLocal(list);
        } else {
            db.put(Entity.BANK_NOTIFICATION, item);
            cleanup(db);
        }
        refreshCount();
        return true;
    }

    /** O item mais recente com esta chave de notificação que chegou há menos de 10 minutos, ou null. */
    public synchronized InboxItem findByKey(String notificationKey, long postedAt) {
        if (notificationKey == null) {
            return null;
        }
        List<InboxItem> all = new ArrayList<>(recognized());
        all.addAll(local());
        InboxItem best = null;
        for (InboxItem i : all) {
            if (notificationKey.equals(i.notificationKey) && Math.abs(postedAt - i.postedAt) < 10 * 60_000L
                    && (best == null || i.postedAt > best.postedAt)) {
                best = i;
            }
        }
        return best;
    }

    /** Troca o conteúdo de um item (mesmo id), movendo entre o aparelho e os dados do usuário se o status mudou. */
    public synchronized void replace(InboxItem item) {
        LocalStore db = syncDb();
        List<InboxItem> list = local();
        boolean removed = list.removeIf(i -> i.id.equals(item.id));
        if (InboxItem.UNRECOGNIZED.equals(item.status) || db == null) {
            list.add(0, item);
            saveLocal(list);
            if (db != null && db.get(Entity.BANK_NOTIFICATION, item.id) != null) {
                db.remove(Entity.BANK_NOTIFICATION, item.id);
            }
        } else {
            if (removed) {
                saveLocal(list);
            }
            db.put(Entity.BANK_NOTIFICATION, item);
        }
        refreshCount();
    }

    public synchronized void setStatus(String id, String status, String transactionId) {
        LocalStore db = db();
        InboxItem cur = db == null ? null : db.get(Entity.BANK_NOTIFICATION, id);
        if (cur != null) {
            InboxItem i = cur.copy();
            i.status = status;
            if (transactionId != null) {
                i.transactionId = transactionId;
            }
            db.put(Entity.BANK_NOTIFICATION, i);
        } else {
            // Guardada só no aparelho (sem o consentimento para sincronizar).
            List<InboxItem> list = local();
            for (InboxItem i : list) {
                if (i.id.equals(id)) {
                    i.status = status;
                    if (transactionId != null) {
                        i.transactionId = transactionId;
                    }
                }
            }
            saveLocal(list);
        }
        refreshCount();
    }

    public synchronized void clearUnrecognized() {
        List<InboxItem> list = local();
        list.removeIf(i -> InboxItem.UNRECOGNIZED.equals(i.status));
        saveLocal(list);
    }

    /**
     * Leva para os dados do usuário as reconhecidas guardadas só no aparelho (chegaram com a sessão fechada, ou
     * são da versão anterior do app, que não sincronizava a caixa). Confirmadas e descartadas antigas são esquecidas.
     */
    public synchronized void migrateLocal() {
        LocalStore db = syncDb();
        if (db == null) {
            return;
        }
        List<InboxItem> list = local();
        boolean changed = false;
        Iterator<InboxItem> it = list.iterator();
        while (it.hasNext()) {
            InboxItem i = it.next();
            if (InboxItem.UNRECOGNIZED.equals(i.status)) {
                continue;
            }
            it.remove();
            changed = true;
            if (InboxItem.PENDING.equals(i.status) && !db.known(Entity.BANK_NOTIFICATION, i.id)) {
                if (i.createdAt == null) {
                    i.createdAt = Dates.nowIso();
                }
                if (i.defaultAccountId == null) {
                    i.defaultAccountId = defaultAccount(i.packageName);
                }
                if (i.defaultCardId == null) {
                    i.defaultCardId = defaultCard(i.packageName);
                }
                db.put(Entity.BANK_NOTIFICATION, i);
            }
        }
        if (changed) {
            saveLocal(list);
        }
    }

    /**
     * Sincronização desligada: as sugestões pendentes voltam para o aparelho e a caixa sai da conta (de todos os
     * aparelhos e do site).
     */
    public synchronized void unsync() {
        LocalStore db = db();
        if (db == null) {
            return;
        }
        List<InboxItem> list = local();
        for (InboxItem i : db.list(Entity.BANK_NOTIFICATION)) {
            if (InboxItem.PENDING.equals(i.status)) {
                list.add(i);
            }
            db.remove(Entity.BANK_NOTIFICATION, i.id);
        }
        list.sort((a, b) -> Long.compare(b.postedAt, a.postedAt));
        saveLocal(list);
        refreshCount();
    }

    public synchronized void refreshCount() {
        pendingCount.postValue(pending().size());
    }

    /** Confirmadas/descartadas com mais de 30 dias saem dos dados (em todos os aparelhos). */
    private static void cleanup(LocalStore db) {
        long now = System.currentTimeMillis();
        for (InboxItem i : db.list(Entity.BANK_NOTIFICATION)) {
            boolean done = InboxItem.CONFIRMED.equals(i.status) || InboxItem.DISCARDED.equals(i.status);
            if (done && now - i.postedAt > KEEP_DONE_MS) {
                db.remove(Entity.BANK_NOTIFICATION, i.id);
            }
        }
    }

    private static boolean same(InboxItem a, InboxItem b) {
        return a.hash != null && a.hash.equals(b.hash) && Math.abs(a.postedAt - b.postedAt) < DUP_WINDOW_MS;
    }

    private List<InboxItem> local() {
        String raw = sp.getString("items", null);
        List<InboxItem> list = raw == null ? null : Json.GSON.fromJson(raw, LIST);
        return list == null ? new ArrayList<>() : list;
    }

    private void saveLocal(List<InboxItem> list) {
        long now = System.currentTimeMillis();
        list.removeIf(i -> (InboxItem.CONFIRMED.equals(i.status) || InboxItem.DISCARDED.equals(i.status))
            && now - i.postedAt > KEEP_DONE_MS);
        while (list.size() > MAX_LOCAL) {
            list.remove(list.size() - 1);
        }
        sp.edit().putString("items", Json.GSON.toJson(list)).apply();
    }

    // ---------- Preferências ----------

    public boolean isMonitored(String pkg) {
        String v = sp.getString("monitor:" + pkg, null);
        if (v != null) {
            return "1".equals(v);
        }
        return BankApps.label(pkg) != null;
    }

    public void setMonitored(String pkg, boolean on) {
        sp.edit().putString("monitor:" + pkg, on ? "1" : "0").apply();
    }

    /** Apps fora da lista conhecida que o usuário adicionou. */
    public List<String> extraApps() {
        String raw = sp.getString("extraApps", "");
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    public void addExtraApp(String pkg) {
        List<String> apps = extraApps();
        if (!apps.contains(pkg)) {
            apps.add(pkg);
            sp.edit().putString("extraApps", String.join(",", apps)).putString("monitor:" + pkg, "1").apply();
        }
    }

    /** Conta (de dinheiro) onde caem Pix/transferências/débitos deste app. */
    public String defaultAccount(String pkg) {
        return sp.getString("account:" + pkg, null);
    }

    public void setDefaultAccount(String pkg, String accountId) {
        sp.edit().putString("account:" + pkg, accountId).apply();
    }

    /** Cartão onde caem as compras no crédito deste app (quando o final do cartão não identifica). */
    public String defaultCard(String pkg) {
        return sp.getString("card:" + pkg, null);
    }

    public void setDefaultCard(String pkg, String accountId) {
        sp.edit().putString("card:" + pkg, accountId).apply();
    }

    /** Mostrar notificação própria com o botão "Confirmar" a cada sugestão. */
    public boolean notifyEnabled() {
        return sp.getBoolean("notify", true);
    }

    public void setNotifyEnabled(boolean on) {
        sp.edit().putBoolean("notify", on).apply();
    }
}
