package br.com.contaslt.ui;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;

import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import br.com.contaslt.AppContainer;
import br.com.contaslt.ContasApp;
import br.com.contaslt.R;
import br.com.contaslt.data.Session;
import br.com.contaslt.databinding.ActivityMainBinding;
import br.com.contaslt.databinding.ItemBottomNavBinding;
import br.com.contaslt.ui.form.TransactionFormSheet;

/**
 * Casca do app (AppShell.vue no celular): cabeçalho com título, sincronização e mês; barra inferior com Início,
 * Lançamentos, Recorrentes, Importar e Mais; botão "+" para novo lançamento. Primeira abertura num aparelho espera
 * a primeira sincronização (SyncGate.vue).
 */
public class MainActivity extends AppCompatActivity {

    private record Tab(int dest, String label, int icon) {
    }

    private static final List<Tab> TABS = Arrays.asList(
        new Tab(R.id.home, "Início", R.drawable.ic_layout_dashboard),
        new Tab(R.id.transactions, "Lançamentos", R.drawable.ic_list_checks),
        new Tab(R.id.recurring, "Recorrentes", R.drawable.ic_calendar_days),
        new Tab(R.id.importStatement, "Importar", R.drawable.ic_file_up),
        new Tab(R.id.more, "Mais", R.drawable.ic_ellipsis));

    /** Telas que mostram a navegação de mês no cabeçalho (meta.monthNav no PWA). */
    private static final Set<Integer> MONTH_NAV = new HashSet<>(Arrays.asList(R.id.home, R.id.transactions, R.id.categorySpending));

    private ActivityMainBinding b;
    private NavController nav;
    private AppContainer app;
    private final ItemBottomNavBinding[] tabViews = new ItemBottomNavBinding[TABS.size()];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = ((ContasApp) getApplication()).container();
        if (!app.auth.isAuthenticated()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        NavHostFragment host = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.navHost);
        nav = host.getNavController();
        buildBottomNav();
        nav.addOnDestinationChangedListener((controller, dest, args) -> onDestination(dest));

        b.back.setOnClickListener(v -> nav.navigateUp());
        b.monthPrev.setOnClickListener(v -> AppState.previousMonth());
        b.monthNext.setOnClickListener(v -> AppState.nextMonth());
        b.monthLabel.setOnClickListener(v -> AppState.thisMonth());
        AppState.month.observe(this, m -> {
            b.monthLabel.setText(Format.monthNav(m));
            b.monthLabel.setTextColor(Ui.color(this, AppState.isCurrentMonth() ? R.color.foreground : R.color.primary));
        });
        b.fab.setOnClickListener(v -> TransactionFormSheet.show(getSupportFragmentManager(), AppState.FormRequest.create()));
        b.sync.setOnClickListener(v -> app.session.scheduleSync(0));
        b.syncGateRetry.setOnClickListener(v -> app.session.scheduleSync(0));
        b.syncGateLogout.setOnClickListener(v -> {
            b.syncGateLogout.setEnabled(false);
            Repo.run(this, () -> {
                app.auth.logout();
                return true;
            }, ok -> toLogin(), e -> toLogin());
        });
        b.inbox.setOnClickListener(v -> nav.navigate(R.id.inbox));
        app.session.state.observe(this, this::onSyncState);
        // Contas e categorias dos formulários e filtros acompanham qualquer mudança nos dados.
        app.session.dataVersion.observe(this, v -> Repo.run(this, () -> {
            if (app.session.isOpen()) {
                Catalog.reload();
            }
            return true;
        }, ok -> {
        }, e -> {
        }));

        // Caixa "A confirmar" (notificações dos bancos): atalho no cabeçalho com a contagem.
        br.com.contaslt.notify.InboxStore.pendingCount.observe(this, this::setInboxCount);
        Repo.run(this, () -> {
            br.com.contaslt.notify.InboxStore inbox = br.com.contaslt.notify.NotificationProcessor.store(this);
            if (inbox != null) {
                inbox.refreshCount();
            }
            return true;
        }, ok -> {
        }, e -> {
        });
        if (getIntent().getBooleanExtra("openInbox", false)) {
            nav.navigate(R.id.inbox);
        }

        app.api.onUnauthorized = e -> runOnUiThread(() -> {
            app.auth.expire();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });

        // Abre o banco local do usuário (rápido: é o arquivo do aparelho).
        Repo.run(this, () -> {
            app.auth.ensureSession();
            return true;
        }, ok -> {
        }, e -> {
        });
    }

    private void buildBottomNav() {
        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < TABS.size(); i++) {
            Tab t = TABS.get(i);
            ItemBottomNavBinding item = ItemBottomNavBinding.inflate(inf, b.bottomNavItems, false);
            item.icon.setImageResource(t.icon());
            item.label.setText(t.label());
            item.getRoot().setOnClickListener(v -> {
                NavDestination cur = nav.getCurrentDestination();
                if (cur != null && cur.getId() == t.dest()) {
                    return;
                }
                nav.navigate(t.dest(), null, new NavOptions.Builder()
                    .setPopUpTo(R.id.home, t.dest() == R.id.home)
                    .setLaunchSingleTop(true)
                    .build());
            });
            tabViews[i] = item;
            b.bottomNavItems.addView(item.getRoot());
        }
    }

    private void onDestination(NavDestination dest) {
        int id = dest.getId();
        b.title.setText(dest.getLabel());
        boolean top = false;
        for (int i = 0; i < TABS.size(); i++) {
            boolean active = TABS.get(i).dest() == id;
            top |= active;
            int color = Ui.color(this, active ? R.color.primary : R.color.muted_foreground);
            tabViews[i].icon.setImageTintList(android.content.res.ColorStateList.valueOf(color));
            tabViews[i].label.setTextColor(color);
        }
        Ui.visible(b.back, !top);
        Ui.visible(b.monthNav, MONTH_NAV.contains(id));
        // Na importação a barra inferior da revisão ("Importar N") ocupa o lugar do "+".
        fabHiddenHere = id == R.id.importStatement;
        Session.SyncState st = app.session.state.getValue();
        Ui.visible(b.fab, !fabHiddenHere && (st == null || st.initialDone));
    }

    private boolean fabHiddenHere;

    private void onSyncState(Session.SyncState s) {
        int icon;
        int tint = R.color.muted_foreground;
        String label;
        switch (s.status) {
            case SYNCING -> {
                icon = R.drawable.ic_refresh_cw;
                label = "Sincronizando…";
            }
            case OFFLINE -> {
                icon = R.drawable.ic_cloud_off;
                label = s.pending > 0 ? "Offline · " + s.pending + " para enviar" : "Offline";
            }
            case ERROR -> {
                icon = R.drawable.ic_cloud_alert;
                tint = R.color.destructive;
                label = s.error != null ? s.error : "Falha ao sincronizar";
            }
            default -> {
                icon = s.pending > 0 ? R.drawable.ic_cloud_upload : R.drawable.ic_cloud_check;
                label = s.pending > 0 ? s.pending + " para enviar" : "Sincronizado";
            }
        }
        b.sync.setImageResource(icon);
        b.sync.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, tint)));
        b.sync.setContentDescription(label);
        b.sync.setTooltipText(label);

        boolean gate = !s.initialDone;
        Ui.visible(b.syncGate, gate);
        Ui.visible(b.fab, !gate && !fabHiddenHere);
        if (gate) {
            boolean failed = s.status == Session.Status.OFFLINE || s.status == Session.Status.ERROR;
            Ui.visible(b.syncGateProgress, !failed);
            Ui.visible(b.syncGateTitle, failed);
            Ui.visible(b.syncGateRetry, failed);
            Ui.visible(b.syncGateLogout, failed);
            b.syncGateText.setText(failed
                ? (s.error != null ? s.error : "Sem conexão. Conecte-se para o primeiro acesso neste aparelho.")
                : "Baixando seus dados para este aparelho…");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("openInbox", false) && nav != null) {
            nav.navigate(R.id.inbox);
        }
    }

    /** Mostra/esconde o atalho da caixa "A confirmar" no cabeçalho (com contagem na descrição). */
    public void setInboxCount(int count) {
        if (b == null) {
            return;
        }
        Ui.visible(b.inbox, count > 0);
        b.inbox.setContentDescription(count + " a confirmar");
    }

    public NavController nav() {
        return nav;
    }

    /** Primeiro acesso travado (servidor errado ou fora do ar): volta para o login sem precisar limpar o app. */
    private void toLogin() {
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }
}
