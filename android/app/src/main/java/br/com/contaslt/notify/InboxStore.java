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

/**
 * Caixa "A confirmar" e lista de não reconhecidas, por usuário, no SharedPreferences (é pouca coisa: guarda as
 * últimas 300 e descarta confirmadas/descartadas com mais de 30 dias). Também guarda as preferências: apps
 * monitorados e a conta padrão de cada um.
 */
public final class InboxStore {

    private static final int MAX = 300;
    private static final long KEEP_DONE_MS = 30L * 86_400_000L;
    private static final Type LIST = new TypeToken<List<InboxItem>>() { }.getType();

    /** Quantas sugestões aguardam confirmação (badge). */
    public static final MutableLiveData<Integer> pendingCount = new MutableLiveData<>(0);

    private final SharedPreferences sp;

    public InboxStore(Context context, long userId) {
        sp = context.getSharedPreferences("contaslt-inbox-u" + userId, Context.MODE_PRIVATE);
    }

    public synchronized List<InboxItem> all() {
        String raw = sp.getString("items", null);
        List<InboxItem> list = raw == null ? null : Json.GSON.fromJson(raw, LIST);
        return list == null ? new ArrayList<>() : list;
    }

    public synchronized InboxItem get(String id) {
        for (InboxItem i : all()) {
            if (i.id.equals(id)) {
                return i;
            }
        }
        return null;
    }

    /** Guarda a notificação; devolve false se já havia uma igual (mesmo hash) nos últimos 3 dias. */
    public synchronized boolean add(InboxItem item) {
        List<InboxItem> list = all();
        for (InboxItem i : list) {
            if (i.hash.equals(item.hash) && Math.abs(i.postedAt - item.postedAt) < 3 * 86_400_000L) {
                return false;
            }
        }
        list.add(0, item);
        save(list);
        return true;
    }

    public synchronized void update(InboxItem item) {
        List<InboxItem> list = all();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(item.id)) {
                list.set(i, item);
            }
        }
        save(list);
    }

    public synchronized void setStatus(String id, String status, String transactionId) {
        List<InboxItem> list = all();
        for (InboxItem i : list) {
            if (i.id.equals(id)) {
                i.status = status;
                if (transactionId != null) {
                    i.transactionId = transactionId;
                }
            }
        }
        save(list);
    }

    public synchronized void clearUnrecognized() {
        List<InboxItem> list = all();
        list.removeIf(i -> InboxItem.UNRECOGNIZED.equals(i.status));
        save(list);
    }

    private void save(List<InboxItem> list) {
        long now = System.currentTimeMillis();
        Iterator<InboxItem> it = list.iterator();
        while (it.hasNext()) {
            InboxItem i = it.next();
            boolean done = InboxItem.CONFIRMED.equals(i.status) || InboxItem.DISCARDED.equals(i.status);
            if (done && now - i.postedAt > KEEP_DONE_MS) {
                it.remove();
            }
        }
        while (list.size() > MAX) {
            list.remove(list.size() - 1);
        }
        sp.edit().putString("items", Json.GSON.toJson(list)).apply();
        int pending = 0;
        for (InboxItem i : list) {
            if (InboxItem.PENDING.equals(i.status)) {
                pending++;
            }
        }
        pendingCount.postValue(pending);
    }

    public synchronized void refreshCount() {
        int pending = 0;
        for (InboxItem i : all()) {
            if (InboxItem.PENDING.equals(i.status)) {
                pending++;
            }
        }
        pendingCount.postValue(pending);
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
