package br.com.contaslt.ui.inbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;


import br.com.contaslt.R;
import br.com.contaslt.databinding.FragmentNotificationSettingsBinding;
import br.com.contaslt.databinding.ItemBankAppBinding;
import br.com.contaslt.notify.BankApps;
import br.com.contaslt.notify.InboxItem;
import br.com.contaslt.notify.InboxStore;
import br.com.contaslt.notify.NotificationProcessor;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Repo;
import br.com.contaslt.ui.Ui;
import br.com.contaslt.ui.Views;

/**
 * Notificações dos bancos: liberar o acesso, escolher os apps lidos (os bancos conhecidos instalados já vêm marcados),
 * a conta e o cartão padrão de cada um, e testar a leitura de um texto.
 */
public class NotificationSettingsFragment extends Fragment {

    private FragmentNotificationSettingsBinding b;
    private InboxStore store;
    private ActivityResultLauncher<String> askPermission;

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        askPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> renderAccess());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        b = FragmentNotificationSettingsBinding.inflate(inflater, container, false);
        store = NotificationProcessor.store(requireContext());
        Views.section(b.appsTitle, "Apps lidos", "Só as notificações destes apps são lidas", "Adicionar", v -> pickApp());
        Views.section(b.testTitle, "Testar a leitura", "Cole o texto de uma notificação do banco", null, null);
        b.notifySwitch.setChecked(store != null && store.notifyEnabled());
        b.notifySwitch.setOnCheckedChangeListener((v, on) -> {
            if (store != null) {
                store.setNotifyEnabled(on);
            }
            if (on) {
                requestPostPermission();
            }
        });
        b.testButton.setOnClickListener(v -> test());
        return b.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        renderAccess();
        renderApps();
    }

    private boolean listenerEnabled() {
        return NotificationManagerCompat.getEnabledListenerPackages(requireContext()).contains(requireContext().getPackageName());
    }

    private void renderAccess() {
        if (b == null) {
            return;
        }
        boolean on = listenerEnabled();
        b.accessTitle.setText(on ? "Acesso às notificações liberado" : "Acesso às notificações desligado");
        b.accessText.setText(on
            ? "O ContasLT lê só as notificações dos apps marcados abaixo, no aparelho, e transforma compras, Pix e pagamentos em sugestões na caixa A confirmar."
            : "Para ler as notificações dos bancos, libere o ContasLT em Acesso a notificações. Só os apps marcados abaixo são lidos, e nada sai do aparelho.");
        b.accessButton.setText(on ? "Abrir configurações do sistema" : "Liberar acesso");
        b.accessButton.setOnClickListener(v -> {
            if (on) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } else {
                NotificationDisclosure.show(requireContext());
            }
        });
        b.accessIcon.setImageResource(on ? R.drawable.ic_bell_ring : R.drawable.ic_bell);
    }

    private void requestPostPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    /** Bancos conhecidos instalados + apps adicionados pelo usuário. */
    private void renderApps() {
        if (b == null || store == null) {
            return;
        }
        PackageManager pm = requireContext().getPackageManager();
        Map<String, String> apps = new LinkedHashMap<>();
        for (BankApps.App a : BankApps.KNOWN) {
            if (installed(pm, a.packageName())) {
                apps.put(a.packageName(), a.label());
            }
        }
        for (String pkg : store.extraApps()) {
            apps.put(pkg, NotificationProcessor.appLabel(requireContext(), pkg));
        }
        b.apps.removeAllViews();
        if (apps.isEmpty()) {
            b.apps.addView(Views.empty(b.apps, R.drawable.ic_smartphone, "Nenhum app de banco encontrado",
                "Toque em Adicionar para escolher o app do seu banco.", null, null));
            return;
        }
        LayoutInflater inf = getLayoutInflater();
        int i = 0;
        for (Map.Entry<String, String> e : apps.entrySet()) {
            if (i++ > 0) {
                b.apps.addView(Views.divider(b.apps));
            }
            String pkg = e.getKey();
            ItemBankAppBinding row = ItemBankAppBinding.inflate(inf, b.apps, false);
            row.appName.setText(e.getValue());
            try {
                row.appIcon.setImageDrawable(pm.getApplicationIcon(pkg));
            } catch (PackageManager.NameNotFoundException ex) {
                row.appIcon.setImageResource(R.drawable.ic_smartphone);
            }
            boolean on = store.isMonitored(pkg);
            row.monitored.setChecked(on);
            Ui.visible(row.defaults, on);
            row.monitored.setOnCheckedChangeListener((v, checked) -> {
                store.setMonitored(pkg, checked);
                Ui.visible(row.defaults, checked);
            });
            row.account.setFilter(a -> !"CREDIT_CARD".equals(a.type));
            row.account.setPlaceholder("Automática");
            row.account.setValue(store.defaultAccount(pkg));
            row.account.setOnChange(id -> store.setDefaultAccount(pkg, id));
            row.card.setFilter(a -> "CREDIT_CARD".equals(a.type));
            row.card.setPlaceholder("Pelo final");
            row.card.setValue(store.defaultCard(pkg));
            row.card.setOnChange(id -> store.setDefaultCard(pkg, id));
            b.apps.addView(row.getRoot());
        }
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Adicionar um app que não está na lista conhecida: o usuário informa o nome do pacote (o app não lista os apps
     * instalados, para não pedir visibilidade ampla de pacotes).
     */
    private void pickApp() {
        android.content.Context c = requireContext();
        android.widget.LinearLayout box = new android.widget.LinearLayout(c);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int p = Ui.dp(c, 20);
        box.setPadding(p, Ui.dp(c, 8), p, 0);
        android.widget.TextView hint = new android.widget.TextView(c, null, 0, R.style.Text_Muted_Sm);
        hint.setText("Informe o nome do pacote do app do banco (ex.: com.nu.production). Ele aparece no endereço do app "
            + "na Play Store, depois de \"id=\".");
        box.addView(hint);
        android.widget.EditText input = new android.widget.EditText(c, null, 0, R.style.Input);
        input.setHint("com.banco.app");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 12);
        box.addView(input, lp);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(c)
            .setTitle("Adicionar app")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Adicionar", (d, w) -> {
                String pkg = input.getText().toString().trim();
                if (pkg.matches("[A-Za-z][\\w]*(\\.[A-Za-z][\\w]*)+")) {
                    store.addExtraApp(pkg);
                    renderApps();
                } else if (!pkg.isEmpty()) {
                    com.google.android.material.snackbar.Snackbar.make(b.getRoot(), "Nome de pacote inválido", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
                }
            })
            .show();
    }

    private void test() {
        String text = b.testText.getText().toString().trim();
        if (text.isEmpty() || store == null) {
            return;
        }
        Repo.run(getViewLifecycleOwner(), b.getRoot(), () -> {
            InboxItem item = NotificationProcessor.toItem(requireContext(), "teste", "Teste", text, System.currentTimeMillis());
            item.appLabel = "Teste";
            item.hash = item.hash + ":" + item.id;
            store.add(item);
            return item;
        }, item -> {
            Ui.visible(b.testResult, true);
            if (InboxItem.UNRECOGNIZED.equals(item.status)) {
                b.testResult.setText("Não reconhecido: o texto foi para a lista Não reconhecidas da caixa A confirmar.");
                b.testResult.setTextColor(Ui.color(requireContext(), R.color.destructive));
            } else {
                b.testResult.setText(("INCOME".equals(item.type) ? "Receita" : "Despesa") + " de " + Format.money(item.amount)
                    + " · " + item.description + " · " + Format.dateShort(item.date)
                    + (item.cardLast4 != null ? " · cartão final " + item.cardLast4 : "") + ". Foi para A confirmar.");
                b.testResult.setTextColor(Ui.color(requireContext(), R.color.income));
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
