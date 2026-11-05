package br.com.contaslt.ui.home;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import br.com.contaslt.ContasApp;
import br.com.contaslt.R;
import br.com.contaslt.databinding.FragmentHomeBinding;
import br.com.contaslt.databinding.ItemAccountRowBinding;
import br.com.contaslt.databinding.ItemCategoryBarBinding;
import br.com.contaslt.databinding.ItemTransactionBinding;
import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.CategoryTotal;
import br.com.contaslt.dto.Dashboard;
import br.com.contaslt.dto.MonthTotal;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.services.DashboardService;
import br.com.contaslt.ui.AppState;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Repo;
import br.com.contaslt.ui.TxActions;
import br.com.contaslt.ui.Ui;
import br.com.contaslt.ui.Views;
import br.com.contaslt.ui.form.TransactionFormSheet;
import br.com.contaslt.ui.transactions.TransactionsFragment;
import br.com.contaslt.ui.views.TransactionRowBinder;

/** Início (DashboardPage.vue): resumo do mês, contas a pagar, últimos 6 meses, despesas por categoria e contas. */
public class HomeFragment extends Fragment implements TransactionRowBinder.Listener {

    private static final int MAX_BILLS = 8;

    private FragmentHomeBinding b;
    private Dashboard data;
    private final java.util.Set<String> busy = new java.util.HashSet<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        b = FragmentHomeBinding.inflate(inflater, container, false);
        b.refresh.setColorSchemeColors(Ui.color(requireContext(), R.color.primary));
        b.refresh.setOnRefreshListener(() -> {
            ((ContasApp) requireActivity().getApplication()).container().session.scheduleSync(0);
            load();
        });
        Views.section(b.billsTitle, "Contas a pagar", null, "Ver todas", v -> openTransactions(TransactionsFragment.args(null, null, "pendente")));
        Views.section(b.historyTitle, "Últimos 6 meses", null, null, null);
        Views.section(b.categoriesTitle, "Despesas por categoria", null, "Ver detalhes", v -> nav(R.id.categorySpending, null));
        Views.section(b.accountsTitle, "Contas", null, "Gerenciar", v -> nav(R.id.accounts, null));
        b.transfers.setOnClickListener(v -> openTransactions(TransactionsFragment.args(null, "TRANSFER", null)));
        b.inboxCard.setOnClickListener(v -> nav(R.id.inbox, null));
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        AppState.month.observe(getViewLifecycleOwner(), m -> load());
        ((ContasApp) requireActivity().getApplication()).container().session.dataVersion
            .observe(getViewLifecycleOwner(), v -> load());
    }

    private void load() {
        String month = AppState.month.getValue();
        Repo.run(getViewLifecycleOwner(), b.getRoot(), () -> DashboardService.buildDashboard(month), d -> {
            b.refresh.setRefreshing(false);
            data = d;
            render();
        });
    }

    private void render() {
        if (b == null || data == null) {
            return;
        }
        Dashboard d = data;
        Ui.money(b.income, d.income, "INCOME", false, false);
        Ui.money(b.expense, d.expense, "EXPENSE", false, false);
        Ui.money(b.balance, d.balance, null, false, false);
        double share = d.expense <= 0 ? 0 : Math.min(1, (double) d.paidExpense / d.expense);
        b.paidBar.setProgress((int) Math.round(share * 1000));

        SpannableStringBuilder paid = new SpannableStringBuilder();
        strong(paid, Format.money(d.paidExpense));
        paid.append(" pagos de ").append(Format.money(d.expense));
        if (d.pendingExpense > 0) {
            paid.append(" · faltam ");
            strong(paid, Format.money(d.pendingExpense));
        }
        b.paidText.setText(paid);

        Ui.visible(b.transfers, d.transfers > 0);
        SpannableStringBuilder tr = new SpannableStringBuilder();
        strong(tr, Format.money(d.transfers));
        tr.append(" movimentados entre suas contas, fora do resultado");
        b.transfersText.setText(tr);

        renderBills(d);
        renderHistory(d.history);
        renderCategories(d.byCategory);
        renderAccounts(d.accounts, d.currentBalance);
    }

    private void renderBills(Dashboard d) {
        Map<String, Transaction> seen = new LinkedHashMap<>();
        for (Transaction t : d.overdue) {
            seen.putIfAbsent(t.id, t);
        }
        for (Transaction t : d.upcoming) {
            seen.putIfAbsent(t.id, t);
        }
        List<Transaction> bills = new ArrayList<>(seen.values());
        Views.section(b.billsTitle, "Contas a pagar", bills.isEmpty() ? null : "Vencidas e dos próximos 7 dias", "Ver todas",
            v -> openTransactions(TransactionsFragment.args(null, null, "pendente")));
        b.bills.removeAllViews();
        b.bills.setBackgroundResource(bills.isEmpty() ? R.drawable.bg_card_dashed : R.drawable.bg_card);
        if (bills.isEmpty()) {
            b.bills.addView(Views.empty(b.bills, R.drawable.ic_calendar_check, "Nada vence nos próximos 7 dias",
                "Contas recorrentes aparecem aqui automaticamente quando o vencimento se aproxima.", null, null));
            return;
        }
        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < Math.min(MAX_BILLS, bills.size()); i++) {
            if (i > 0) {
                b.bills.addView(Views.divider(b.bills));
            }
            ItemTransactionBinding row = TransactionRowBinder.inflate(inf, b.bills);
            TransactionRowBinder.bind(row, bills.get(i), true, busy.contains(bills.get(i).id), this);
            b.bills.addView(row.getRoot());
        }
        int hidden = bills.size() - MAX_BILLS;
        if (hidden > 0) {
            b.bills.addView(Views.divider(b.bills));
            android.widget.TextView more = new android.widget.TextView(requireContext(), null, 0, R.style.Text_Semibold_Sm);
            more.setText("Mais " + hidden + (hidden == 1 ? " pendente" : " pendentes"));
            more.setTextColor(Ui.color(requireContext(), R.color.primary));
            more.setGravity(android.view.Gravity.CENTER);
            int p = Ui.dp(requireContext(), 12);
            more.setPadding(0, p, 0, p);
            more.setOnClickListener(v -> openTransactions(TransactionsFragment.args(null, null, "pendente")));
            b.bills.addView(more);
        }
    }

    private void renderHistory(List<MonthTotal> history) {
        String month = AppState.month.getValue();
        b.history.setData(history, month, m -> AppState.month.setValue(m));
        for (MonthTotal m : history) {
            if (m.month.equals(month)) {
                SpannableStringBuilder s = new SpannableStringBuilder(Format.monthShortLabel(m.month) + ": ");
                color(s, "+" + Format.money(m.income), R.color.income);
                s.append(" / ");
                color(s, "−" + Format.money(m.expense), R.color.expense);
                b.historyActive.setText(s);
            }
        }
    }

    private void renderCategories(List<CategoryTotal> items) {
        b.categories.removeAllViews();
        b.categories.setBackgroundResource(items.isEmpty() ? R.drawable.bg_card_dashed : R.drawable.bg_card);
        if (items.isEmpty()) {
            b.categories.addView(Views.empty(b.categories, 0, "Sem despesas neste mês",
                "Adicione uma despesa ou importe o extrato do banco.", "Importar extrato", v -> nav(R.id.importStatement, null)));
            return;
        }
        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < items.size(); i++) {
            CategoryTotal item = items.get(i);
            if (i > 0) {
                b.categories.addView(Views.divider(b.categories));
            }
            ItemCategoryBarBinding row = ItemCategoryBarBinding.inflate(inf, b.categories, false);
            row.icon.setSize("sm");
            row.icon.bind(item.icon, item.color);
            row.name.setText(item.name);
            row.total.setText(Format.money(item.total));
            row.bar.setProgress((int) Math.round(Math.max(2, item.percent) * 10));
            row.bar.getProgressDrawable().mutate();
            android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) row.bar.getProgressDrawable();
            ld.findDrawableByLayerId(android.R.id.progress).setTint(Ui.parseColor(item.color, Ui.color(requireContext(), R.color.primary)));
            row.percent.setText(String.format(Format.PT_BR, "%.0f%%", item.percent));
            row.getRoot().setOnClickListener(v -> openTransactions(TransactionsFragment.args(
                item.categoryId == null ? "nenhuma" : item.categoryId, "EXPENSE", null)));
            b.categories.addView(row.getRoot());
        }
    }

    private void renderAccounts(List<Account> accounts, long total) {
        b.accounts.removeAllViews();
        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < accounts.size(); i++) {
            Account a = accounts.get(i);
            if (i > 0) {
                b.accounts.addView(Views.divider(b.accounts));
            }
            ItemAccountRowBinding row = ItemAccountRowBinding.inflate(inf, b.accounts, false);
            row.icon.setSize("sm");
            row.icon.bind("wallet", a.color);
            row.name.setText(a.name);
            row.type.setText(Format.accountTypeLabel(a.type));
            Ui.money(row.balance, a.balance, null, false, false);
            b.accounts.addView(row.getRoot());
        }
        Ui.money(b.currentBalance, total, null, false, false);
    }

    private static void strong(SpannableStringBuilder s, String text) {
        int start = s.length();
        s.append(text);
        s.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void color(SpannableStringBuilder s, String text, int color) {
        int start = s.length();
        s.append(text);
        s.setSpan(new ForegroundColorSpan(Ui.color(requireContext(), color)), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void nav(int dest, Bundle args) {
        NavHostFragment.findNavController(this).navigate(dest, args);
    }

    private void openTransactions(Bundle args) {
        nav(R.id.transactions, args);
    }

    // ---------- Linha de lançamento ----------

    @Override
    public void open(Transaction t) {
        TransactionFormSheet.show(getParentFragmentManager(), AppState.FormRequest.edit(t));
    }

    @Override
    public void togglePaid(Transaction t) {
        busy.add(t.id);
        render();
        TxActions.togglePaid(getViewLifecycleOwner(), b.getRoot(), t, () -> busy.remove(t.id));
    }

    @Override
    public void receiptScan(Transaction t, String action) {
        busy.add(t.id);
        render();
        TxActions.receiptScan(getViewLifecycleOwner(), b.getRoot(), t, action, () -> busy.remove(t.id));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
