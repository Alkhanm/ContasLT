package br.com.contaslt.ui.more;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import br.com.contaslt.AppContainer;
import br.com.contaslt.ContasApp;
import br.com.contaslt.R;
import br.com.contaslt.databinding.FragmentMoreBinding;
import br.com.contaslt.databinding.ItemMoreLinkBinding;
import br.com.contaslt.databinding.ItemMoreRuleBinding;
import br.com.contaslt.dto.BackupRestored;
import br.com.contaslt.dto.CategoryRule;
import br.com.contaslt.dto.User;
import br.com.contaslt.services.BackupService;
import br.com.contaslt.services.CatalogService;
import br.com.contaslt.services.ReceiptsService;
import br.com.contaslt.ui.LoginActivity;
import br.com.contaslt.ui.Repo;
import br.com.contaslt.ui.Ui;
import br.com.contaslt.ui.Views;
import br.com.contaslt.ui.views.UiKit;

/**
 * Mais (SettingsPage.vue): perfil e Sair, aparência, atalhos de relatórios e organização, notificações dos bancos
 * (só no Android), exportar/importar dados (premium) e regras de categorização.
 */
public class MoreFragment extends Fragment {

    private static final String[][] THEMES = {
        {"light", "Claro"}, {"dark", "Escuro"}, {"system", "Sistema"},
    };

    private FragmentMoreBinding b;
    private AppContainer app;
    private List<CategoryRule> rules = new ArrayList<>();
    private boolean savingRule;
    /** "export" | "import" | null. */
    private String backupBusy;

    private final ActivityResultLauncher<String> exportLauncher =
        registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), this::writeBackup);
    private final ActivityResultLauncher<String[]> importLauncher =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::pickBackup);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        b = FragmentMoreBinding.inflate(inflater, container, false);
        app = ((ContasApp) requireActivity().getApplication()).container();
        Context c = requireContext();

        User user = app.prefs.user();
        String name = user != null && user.name != null ? user.name : "?";
        GradientDrawable avatar = new GradientDrawable();
        avatar.setShape(GradientDrawable.OVAL);
        avatar.setColor(Ui.color(c, R.color.primary));
        b.avatar.setBackground(avatar);
        b.avatar.setText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
        b.userName.setText(user != null ? user.name : null);
        b.userEmail.setText(user != null ? user.email : null);
        b.logout.setOnClickListener(v -> logout());
        b.deleteAccount.setOnClickListener(v -> confirmDeleteAccount());
        b.privacy.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
            android.net.Uri.parse(br.com.contaslt.BuildConfig.PRIVACY_URL))));

        Views.section(b.themeTitle, "Aparência", null, null, null);
        renderThemes();

        Views.section(b.reportsTitle, "Relatórios", null, null, null);
        addLink(b.reports, R.drawable.ic_chart_pie, "Gastos por categoria", R.id.categorySpending);
        addLink(b.reports, R.drawable.ic_shopping_basket, "Gasto por itens", R.id.itemSpending);

        Views.section(b.orgTitle, "Organização", null, null, null);
        addLink(b.org, R.drawable.ic_tag, "Categorias", R.id.categories);
        addLink(b.org, R.drawable.ic_wallet, "Contas", R.id.accounts);
        addLink(b.org, R.drawable.ic_bell, "Notificações dos bancos", R.id.notificationSettings);

        Ui.visible(b.backupSection, user != null && user.premium);
        Views.section(b.backupTitle, "Exportar e importar",
            "Um .zip com os seus dados: contas, categorias, regras, recorrentes, lançamentos com itens e as fotos dos comprovantes.", null, null);
        b.exportBackup.setOnClickListener(v -> exportLauncher.launch("contaslt-dados-" + java.time.LocalDate.now() + ".zip"));
        b.importBackup.setOnClickListener(v -> importLauncher.launch(new String[] {"application/zip", "application/x-zip-compressed", "*/*"}));
        renderBackup();

        Views.section(b.rulesTitle, "Regras de categorização", "Se a descrição contiver a palavra, a categoria é sugerida na importação.", null, null);
        b.ruleType.setOptions(new String[] {"Despesa", "Receita"}, new String[] {"EXPENSE", "INCOME"});
        b.ruleType.setOnChange(t -> {
            b.ruleCategory.setType(t);
            b.ruleCategory.setValue(null);
            renderAddRule();
        });
        b.ruleCategory.setAllowNone(false, null);
        b.ruleCategory.setType("EXPENSE");
        b.ruleCategory.setOnChange(id -> renderAddRule());
        b.keyword.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                renderAddRule();
            }
        });
        b.addRule.setOnClickListener(v -> addRule());
        renderAddRule();
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        app.session.dataVersion.observe(getViewLifecycleOwner(), v -> loadRules());
        br.com.contaslt.ui.Catalog.live.observe(getViewLifecycleOwner(), c -> b.ruleCategory.refresh());
    }

    // ---------- Aparência ----------

    private void renderThemes() {
        Context c = requireContext();
        b.themes.removeAllViews();
        String current = app.prefs.theme();
        int[] icons = {R.drawable.ic_sun, R.drawable.ic_moon, R.drawable.ic_monitor};
        for (int i = 0; i < THEMES.length; i++) {
            String key = THEMES[i][0];
            boolean sel = key.equals(current);
            int color = Ui.color(c, sel ? R.color.primary : R.color.muted_foreground);
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            int p = Ui.dp(c, 12);
            box.setPadding(p, p, p, p);
            box.setBackground(UiKit.box(c, Ui.color(c, R.color.card), Ui.color(c, sel ? R.color.primary : R.color.border), 12, sel ? 2 : 1));
            ImageView icon = new ImageView(c);
            icon.setImageResource(icons[i]);
            icon.setImageTintList(ColorStateList.valueOf(color));
            box.addView(icon, new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)));
            TextView label = UiKit.text(c, R.style.Text_Semibold_Sm, THEMES[i][1]);
            label.setTextColor(color);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = Ui.dp(c, 6);
            box.addView(label, llp);
            box.setOnClickListener(v -> {
                if (key.equals(app.prefs.theme())) {
                    return;
                }
                app.prefs.setTheme(key);
                renderThemes();
                ContasApp.applyTheme(key);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) {
                lp.setMarginStart(Ui.dp(c, 8));
            }
            b.themes.addView(box, lp);
        }
    }

    private void addLink(LinearLayout card, int icon, String label, int dest) {
        if (card.getChildCount() > 0) {
            card.addView(Views.divider(card));
        }
        ItemMoreLinkBinding r = ItemMoreLinkBinding.inflate(getLayoutInflater(), card, false);
        r.icon.setImageResource(icon);
        r.label.setText(label);
        r.getRoot().setOnClickListener(v -> NavHostFragment.findNavController(this).navigate(dest));
        card.addView(r.getRoot());
    }

    // ---------- Sair ----------

    private void logout() {
        b.logout.setEnabled(false);
        Repo.run(getViewLifecycleOwner(), () -> {
            app.auth.logout();
            return true;
        }, ok -> goToLogin(), e -> goToLogin());
    }

    // ---------- Excluir conta ----------

    /** Pede a senha e apaga a conta no servidor e os dados deste aparelho. */
    private void confirmDeleteAccount() {
        Context c = requireContext();
        android.widget.LinearLayout box = new android.widget.LinearLayout(c);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int p = Ui.dp(c, 20);
        box.setPadding(p, Ui.dp(c, 8), p, 0);
        TextView text = UiKit.text(c, R.style.Text_Muted_Sm,
            "Apaga a sua conta e todos os dados do servidor e deste aparelho: contas, categorias, lançamentos, "
                + "recorrentes e fotos dos comprovantes. Não dá para desfazer. Para guardar uma cópia antes, use Exportar dados.");
        box.addView(text);
        android.widget.EditText password = new android.widget.EditText(c, null, 0, R.style.Input);
        password.setHint("Sua senha");
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        // O tipo senha troca a fonte para monoespaçada; volta a Manrope do app.
        password.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(c, R.font.manrope));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 16);
        box.addView(password, lp);
        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(c)
            .setTitle("Excluir minha conta?")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Excluir", null)
            .create();
        dialog.setOnShowListener(d -> {
            android.widget.Button ok = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
            ok.setTextColor(Ui.color(c, R.color.destructive));
            ok.setOnClickListener(v -> {
                String pw = password.getText().toString();
                if (pw.isEmpty()) {
                    password.setError("Informe a senha");
                    return;
                }
                ok.setEnabled(false);
                Repo.run(getViewLifecycleOwner(), () -> {
                    app.auth.deleteAccount(pw);
                    return true;
                }, done -> {
                    dialog.dismiss();
                    UiKit.toast(getActivity(), "Conta excluída");
                    goToLogin();
                }, e -> {
                    ok.setEnabled(true);
                    password.setError(e.status == 401 ? "Senha incorreta" : e.getMessage());
                });
            });
        });
        dialog.show();
    }

    private void goToLogin() {
        if (getActivity() == null) {
            return;
        }
        startActivity(new Intent(requireActivity(), LoginActivity.class));
        requireActivity().finish();
    }

    // ---------- Exportar e importar (premium) ----------

    private void renderBackup() {
        b.exportBackup.setEnabled(backupBusy == null);
        b.importBackup.setEnabled(backupBusy == null);
        b.exportBackup.setText("export".equals(backupBusy) ? "Gerando…" : "Exportar dados");
        b.importBackup.setText("import".equals(backupBusy) ? "Importando…" : "Importar dados");
    }

    private void writeBackup(Uri uri) {
        if (uri == null || b == null) {
            return;
        }
        backupBusy = "export";
        renderBackup();
        android.content.ContentResolver cr = requireContext().getContentResolver();
        Repo.run(getViewLifecycleOwner(), () -> {
            byte[] zip = BackupService.exportBackup(ReceiptsService.io());
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) {
                    throw new java.io.IOException("Não foi possível gravar o arquivo");
                }
                out.write(zip);
            }
            return true;
        }, ok -> {
            backupBusy = null;
            renderBackup();
            UiKit.toast(getActivity(), "Arquivo gerado");
        }, e -> {
            backupBusy = null;
            renderBackup();
            UiKit.toast(getActivity(), e.getMessage());
        });
    }

    private void pickBackup(Uri uri) {
        if (uri == null || b == null) {
            return;
        }
        String name = displayName(uri);
        UiKit.confirm(requireContext(), "Importar este arquivo?",
            "Os dados de " + (name != null ? name : "arquivo") + " serão somados aos desta conta. Nada do que já existe é apagado; lançamentos iguais não entram de novo.",
            "Importar", false, () -> restoreBackup(uri));
    }

    private String displayName(Uri uri) {
        try (Cursor cur = requireContext().getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                return cur.getString(0);
            }
        } catch (RuntimeException ignored) {
            // sem nome: usa o texto padrão
        }
        return null;
    }

    private void restoreBackup(Uri uri) {
        backupBusy = "import";
        renderBackup();
        android.content.ContentResolver cr = requireContext().getContentResolver();
        Repo.run(getViewLifecycleOwner(), () -> BackupService.importBackup(readAll(cr, uri), ReceiptsService.io()), r -> {
            backupBusy = null;
            renderBackup();
            UiKit.toast(getActivity(), restoredMessage(r));
        }, e -> {
            backupBusy = null;
            renderBackup();
            UiKit.toast(getActivity(), e.getMessage());
        });
    }

    static byte[] readAll(android.content.ContentResolver cr, Uri uri) throws java.io.IOException {
        try (InputStream in = cr.openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) {
                throw new java.io.IOException("Não foi possível ler o arquivo");
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String restoredMessage(BackupRestored r) {
        List<String> parts = new ArrayList<>();
        parts.add(UiKit.plural(r.transactions, "lançamento", "lançamentos"));
        if (r.accounts > 0) {
            parts.add(UiKit.plural(r.accounts, "conta", "contas"));
        }
        if (r.categories > 0) {
            parts.add(UiKit.plural(r.categories, "categoria", "categorias"));
        }
        if (r.recurringBills > 0) {
            parts.add(UiKit.plural(r.recurringBills, "recorrente", "recorrentes"));
        }
        if (r.receipts > 0) {
            parts.add(UiKit.plural(r.receipts, "comprovante", "comprovantes"));
        }
        return "Importados: " + String.join(", ", parts) + (r.skippedTransactions > 0 ? " (" + r.skippedTransactions + " já existiam)" : "");
    }

    // ---------- Regras de categorização ----------

    private void loadRules() {
        Repo.run(getViewLifecycleOwner(), b.getRoot(), CatalogService::listRules, list -> {
            rules = list;
            renderRules();
        });
    }

    private void renderAddRule() {
        boolean ok = !savingRule && !b.keyword.getText().toString().trim().isEmpty() && b.ruleCategory.value() != null;
        b.addRule.setEnabled(ok);
        b.addRule.setAlpha(ok ? 1f : 0.5f);
    }

    private void renderRules() {
        if (b == null) {
            return;
        }
        b.rules.removeAllViews();
        Ui.visible(b.rules, !rules.isEmpty());
        Ui.visible(b.rulesEmpty, rules.isEmpty());
        LayoutInflater inf = getLayoutInflater();
        for (CategoryRule r : rules) {
            if (b.rules.getChildCount() > 0) {
                b.rules.addView(Views.divider(b.rules));
            }
            ItemMoreRuleBinding row = ItemMoreRuleBinding.inflate(inf, b.rules, false);
            row.keyword.setText("“" + r.keyword + "”");
            row.icon.setSize("sm");
            row.icon.bind(r.categoryIcon, r.categoryColor);
            row.category.setText(r.categoryName);
            row.remove.setOnClickListener(v -> Repo.runAction(getViewLifecycleOwner(), b.getRoot(), () -> CatalogService.deleteRule(r.id), null));
            b.rules.addView(row.getRoot());
        }
    }

    private void addRule() {
        String keyword = b.keyword.getText().toString();
        String categoryId = b.ruleCategory.value();
        if (keyword.trim().isEmpty() || categoryId == null) {
            return;
        }
        savingRule = true;
        renderAddRule();
        Repo.run(getViewLifecycleOwner(), () -> CatalogService.createRule(keyword, categoryId), ok -> {
            savingRule = false;
            b.keyword.setText("");
            renderAddRule();
            UiKit.toast(getActivity(), "Regra salva");
        }, e -> {
            savingRule = false;
            renderAddRule();
            UiKit.toast(getActivity(), e.getMessage());
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
