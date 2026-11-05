package br.com.contaslt.ui.transactions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import br.com.contaslt.ContasApp;
import br.com.contaslt.R;
import br.com.contaslt.databinding.FragmentTransactionsBinding;
import br.com.contaslt.databinding.FragmentTransactionsHeaderBinding;
import br.com.contaslt.databinding.ItemTransactionBinding;
import br.com.contaslt.databinding.ItemTxDayBinding;
import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.CardInvoice;
import br.com.contaslt.dto.Category;
import br.com.contaslt.dto.DayBalance;
import br.com.contaslt.dto.Summary;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.dto.TransactionFilter;
import br.com.contaslt.dto.TransactionPage;
import br.com.contaslt.dto.User;
import br.com.contaslt.services.TransactionsService;
import br.com.contaslt.services.TransfersService;
import br.com.contaslt.ui.AppState;
import br.com.contaslt.ui.Catalog;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Repo;
import br.com.contaslt.ui.TxActions;
import br.com.contaslt.ui.Ui;
import br.com.contaslt.ui.Views;
import br.com.contaslt.ui.form.TransactionFormSheet;
import br.com.contaslt.ui.form.TransferMatchSheet;
import br.com.contaslt.ui.views.MoneyText;
import br.com.contaslt.ui.views.SegmentedControl;
import br.com.contaslt.ui.views.SelectFieldView;
import br.com.contaslt.ui.views.TransactionRowBinder;

/**
 * Lançamentos (TransactionsPage.vue): abas de tipo, busca, situação, conta e categoria; fatura do cartão filtrado;
 * possíveis transferências; resumo do mês e a lista agrupada por dia, com o saldo ao fim de cada dia.
 */
public class TransactionsFragment extends Fragment implements TransactionRowBinder.Listener {

    private static final String ALL = "ALL";
    private static final String TRANSFER = "TRANSFER";
    private static final String STATUS_ALL = "all";

    /** Filtros iniciais vindos de outras telas: categoria (id ou "nenhuma"), tipo (EXPENSE, INCOME, TRANSFER) e status ("pendente"). */
    public static Bundle args(String categoria, String tipo, String status) {
        Bundle b = new Bundle();
        b.putString("categoria", categoria);
        b.putString("tipo", tipo);
        b.putString("status", status);
        return b;
    }

    private FragmentTransactionsBinding b;
    private FragmentTransactionsHeaderBinding h;
    private final Adapter adapter = new Adapter();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable searchDebounce = this::load;

    // Filtros (sobrevivem à volta pela pilha de navegação: o fragmento é mantido, só a view é refeita).
    private String typeFilter = ALL;
    private String status = STATUS_ALL;
    private String accountId;
    private String categoryId;
    private boolean uncategorized;
    private String search = "";
    private boolean byInvoice = true;
    private boolean openMatch;

    private TransactionPage page;
    private int suggestionCount;
    private boolean loading = true;
    private int loadToken;
    private boolean loadedAsInvoice;
    private final Set<String> busy = new HashSet<>();

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        Bundle a = getArguments();
        if (a != null) {
            String tipo = a.getString("tipo");
            if (tipo != null && Arrays.asList("EXPENSE", "INCOME", TRANSFER).contains(tipo)) {
                typeFilter = tipo;
            }
            String st = a.getString("status");
            if ("pendente".equals(st) || "pago".equals(st)) {
                status = st;
            }
            String cat = a.getString("categoria");
            if ("nenhuma".equals(cat)) {
                uncategorized = true;
            } else if (cat != null) {
                categoryId = cat;
            }
            accountId = a.getString("conta");
            byInvoice = !"0".equals(a.getString("fatura"));
            openMatch = "1".equals(a.getString("cruzar"));
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        b = FragmentTransactionsBinding.inflate(inflater, container, false);
        h = FragmentTransactionsHeaderBinding.inflate(inflater, b.list, false);
        b.refresh.setColorSchemeColors(Ui.color(requireContext(), R.color.primary));
        b.refresh.setOnRefreshListener(() -> {
            ((ContasApp) requireActivity().getApplication()).container().session.scheduleSync(0);
            load();
        });
        b.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.list.setAdapter(adapter);
        b.list.setItemAnimator(null);
        setupFilters();
        return b.getRoot();
    }

    private void setupFilters() {
        Context c = requireContext();
        h.typeTabs.setOptions(new String[] {"Tudo", "Despesas", "Receitas", "Transfer."}, new String[] {ALL, "EXPENSE", "INCOME", TRANSFER});
        compactTabs(h.typeTabs, 12, 32);
        h.typeTabs.setValue(typeFilter);
        h.typeTabs.setOnChange(v -> {
            typeFilter = v;
            String type = categoryType();
            Category cat = Catalog.get().category(categoryId);
            if (type != null && cat != null && !type.equals(cat.type)) {
                categoryId = null;
                h.category.setValue(null);
            }
            h.category.setType(type);
            load();
        });

        h.search.setText(search);
        h.search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.toString().equals(search)) {
                    return;
                }
                search = s.toString();
                main.removeCallbacks(searchDebounce);
                main.postDelayed(searchDebounce, 300);
            }
        });

        h.status.setSheetTitle("Situação");
        h.status.setOptions(Arrays.asList(SelectFieldView.Option.of(STATUS_ALL, "Todos"),
            SelectFieldView.Option.of("pendente", "Pendentes"), SelectFieldView.Option.of("pago", "Pagos")));
        h.status.setValue(status);
        h.status.setOnChange(v -> {
            status = v;
            load();
        });

        h.account.setAllowAll(true);
        h.account.setValue(accountId);
        h.account.setOnChange(v -> {
            accountId = v;
            load();
        });

        h.category.setAllowNone(true, "Todas as categorias");
        h.category.setAllowUncategorized(true);
        h.category.setAllLabel("Todas");
        h.category.setType(categoryType());
        if (uncategorized) {
            h.category.setUncategorized(true);
        } else {
            h.category.setValue(categoryId);
        }
        h.category.setOnChange(v -> {
            categoryId = v;
            uncategorized = h.category.uncategorized();
            load();
        });

        h.invoiceMode.setOptions(new String[] {"Por fatura", "Por mês"}, new String[] {"invoice", "month"});
        compactTabs(h.invoiceMode, 12, 26);
        h.invoiceMode.setPadding(Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2));
        h.invoiceMode.setValue(byInvoice ? "invoice" : "month");
        h.invoiceMode.setOnChange(v -> {
            byInvoice = "invoice".equals(v);
            load();
        });
        h.invoicePay.setOnClickListener(v -> payInvoice());
        h.invoiceOpenPayment.setOnClickListener(v -> openPayment());
        h.suggestionsReview.setOnClickListener(v -> openMatchSheet());
    }

    /** Abas menores (text-xs, h-8) que o SegmentedControl padrão. */
    private void compactTabs(SegmentedControl seg, int textSp, int heightDp) {
        for (int i = 0; i < seg.getChildCount(); i++) {
            if (seg.getChildAt(i) instanceof TextView tv) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
                tv.setMinHeight(Ui.dp(requireContext(), heightDp));
                tv.setMinimumHeight(Ui.dp(requireContext(), heightDp));
                if (seg.getLayoutParams() != null && seg.getLayoutParams().width == ViewGroup.LayoutParams.WRAP_CONTENT) {
                    tv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    int p = Ui.dp(requireContext(), 8);
                    tv.setPadding(p, 0, p, 0);
                }
            }
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        AppState.month.observe(getViewLifecycleOwner(), m -> load());
        ((ContasApp) requireActivity().getApplication()).container().session.dataVersion
            .observe(getViewLifecycleOwner(), v -> load());
        Catalog.live.observe(getViewLifecycleOwner(), cat -> {
            h.account.refresh();
            h.category.refresh();
            // O catálogo chegou depois: a conta filtrada pode ser um cartão (lista por fatura).
            if (loadedAsInvoice != (card() != null && byInvoice)) {
                load();
            } else {
                render();
            }
        });
        if (openMatch) {
            openMatch = false;
            openMatchSheet();
        }
    }

    /** Tipo das categorias oferecidas no filtro; sem tipo (Tudo/Transferências) mostra despesas e receitas agrupadas. */
    private String categoryType() {
        return "EXPENSE".equals(typeFilter) || "INCOME".equals(typeFilter) ? typeFilter : null;
    }

    /** Conta filtrada é um cartão de crédito: a lista pode seguir a fatura em vez do mês civil. */
    private Account card() {
        Account a = Catalog.get().account(accountId);
        return a != null && "CREDIT_CARD".equals(a.type) ? a : null;
    }

    private void load() {
        if (b == null) {
            return;
        }
        main.removeCallbacks(searchDebounce);
        TransactionFilter f = new TransactionFilter();
        f.month = AppState.month.getValue();
        f.type = ALL.equals(typeFilter) || TRANSFER.equals(typeFilter) ? null : typeFilter;
        f.transfers = TRANSFER.equals(typeFilter) ? Boolean.TRUE : null;
        f.paid = STATUS_ALL.equals(status) ? null : "pago".equals(status);
        f.accountId = accountId;
        f.categoryId = categoryId;
        f.uncategorized = uncategorized;
        f.search = search.trim().isEmpty() ? null : search.trim();
        f.invoice = card() != null && byInvoice;
        loadedAsInvoice = f.invoice;
        User user = ((ContasApp) requireActivity().getApplication()).container().prefs.user();
        String userName = user == null ? null : user.name;
        int token = ++loadToken;
        if (page == null) {
            loading = true;
            render();
        }
        Repo.run(getViewLifecycleOwner(), b.getRoot(), () -> {
            TransactionPage p = TransactionsService.listTransactions(f);
            int count;
            try {
                count = TransfersService.transferSuggestions(userName).items.size();
            } catch (RuntimeException e) {
                count = 0;
            }
            return new Object[] {p, count};
        }, r -> {
            if (b == null || token != loadToken) {
                return;
            }
            b.refresh.setRefreshing(false);
            page = (TransactionPage) r[0];
            suggestionCount = (Integer) r[1];
            loading = false;
            render();
        });
    }

    // ---------- Renderização ----------

    private void render() {
        if (b == null) {
            return;
        }
        Context c = requireContext();
        renderInvoice(c);
        renderSuggestions(c);
        renderSummary(c);

        List<Group> groups = new ArrayList<>();
        Map<String, Long> dayBalance = new HashMap<>();
        if (page != null && !loading) {
            for (DayBalance d : page.dayBalances) {
                dayBalance.put(d.date, d.balance);
            }
            Map<String, List<Transaction>> map = new LinkedHashMap<>();
            for (Transaction t : page.items) {
                map.computeIfAbsent(t.dueDate, k -> new ArrayList<>()).add(t);
            }
            for (Map.Entry<String, List<Transaction>> e : map.entrySet()) {
                groups.add(new Group(e.getKey(), e.getValue(), dayBalance.get(e.getKey())));
            }
        }

        h.skeleton.removeAllViews();
        Ui.visible(h.skeleton, loading);
        if (loading) {
            for (int i = 0; i < 6; i++) {
                View s = new View(c);
                GradientDrawable g = new GradientDrawable();
                g.setColor(Ui.color(c, R.color.muted));
                g.setCornerRadius(Ui.dp(c, 12));
                s.setBackground(g);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 56));
                if (i > 0) {
                    lp.topMargin = Ui.dp(c, 8);
                }
                h.skeleton.addView(s, lp);
            }
        }

        boolean empty = !loading && groups.isEmpty();
        Ui.visible(h.empty, empty);
        h.empty.removeAllViews();
        if (empty) {
            boolean transfers = TRANSFER.equals(typeFilter);
            String text = transfers ? "Transferências entre suas contas aparecem aqui e não contam como despesa nem receita."
                : !search.isEmpty() || !ALL.equals(typeFilter) || !STATUS_ALL.equals(status) || categoryId != null
                    ? "Tente limpar os filtros." : "Toque em + para adicionar uma despesa ou receita.";
            h.empty.addView(Views.empty(h.empty, transfers ? R.drawable.ic_arrow_left_right : R.drawable.ic_list_checks,
                transfers ? "Nenhuma transferência neste mês" : "Nenhum lançamento aqui", text,
                transfers ? "Procurar pares entre contas" : null, transfers ? v -> openMatchSheet() : null));
        }
        adapter.setGroups(groups);
    }

    private void renderInvoice(Context c) {
        Account card = card();
        Ui.visible(h.invoiceCard, card != null);
        if (card == null) {
            return;
        }
        CardInvoice inv = page == null ? null : page.invoice;
        h.invoiceTitle.setText(inv != null ? "Fatura de " + Format.monthLabel(inv.month).toLowerCase(Format.PT_BR) : card.name);
        Ui.visible(h.invoiceStatus, inv != null);
        Ui.visible(h.invoiceInfo, inv != null);
        Ui.visible(h.invoiceDetails, inv != null);
        if (inv == null) {
            return;
        }
        int statusColor = switch (inv.status == null ? "OPEN" : inv.status) {
            case "CLOSED", "PARTIAL" -> R.color.warning;
            case "PAID" -> R.color.income;
            default -> R.color.primary;
        };
        int col = Ui.color(c, statusColor);
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(Ui.dp(c, 999));
        chip.setColor((col & 0x00FFFFFF) | ((statusColor == R.color.primary ? 0x1A : 0x26) << 24));
        h.invoiceStatus.setBackground(chip);
        h.invoiceStatus.setTextColor(col);
        h.invoiceStatus.setText(Format.invoiceStatusLabel(inv.status == null ? "OPEN" : inv.status));

        if (!inv.configured) {
            SpannableStringBuilder s = new SpannableStringBuilder(
                "Este cartão não tem fechamento e vencimento cadastrados, então a fatura segue o mês civil. ");
            int start = s.length();
            s.append("Cadastrar");
            s.setSpan(new android.text.style.ForegroundColorSpan(Ui.color(c, R.color.primary)), start, s.length(), 0);
            s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, s.length(), 0);
            h.invoiceInfo.setText(s);
            h.invoiceInfo.setOnClickListener(v -> NavHostFragment.findNavController(this).navigate(R.id.accounts));
        } else {
            h.invoiceInfo.setText("Compras de " + Format.dateShort(inv.start) + " a " + Format.dateShort(inv.end)
                + " · vence em " + Format.dateShort(inv.dueDate));
            h.invoiceInfo.setOnClickListener(null);
            h.invoiceInfo.setClickable(false);
        }
        SpannableStringBuilder purchases = new SpannableStringBuilder();
        MoneyText.append(purchases, c, inv.purchases, null, false, false, true);
        int start = purchases.length();
        purchases.append(" (").append(String.valueOf(inv.count)).append(")");
        purchases.setSpan(new android.text.style.ForegroundColorSpan(Ui.color(c, R.color.muted_foreground)), start, purchases.length(), 0);
        purchases.setSpan(new android.text.style.RelativeSizeSpan(12f / 14f), start, purchases.length(), 0);
        h.invoicePurchases.setText(purchases);
        Ui.money(h.invoicePaid, inv.paid, null, false, inv.paid == 0);
        Ui.money(h.invoiceUndetailed, inv.undetailed, null, false, inv.undetailed == 0);
        h.invoiceTotalLabel.setText(inv.paid > 0 ? "Total pago" : "A pagar");
        Ui.money(h.invoiceTotal, inv.paid > 0 ? inv.paid : inv.purchases, null, false, false);
        h.invoiceNote.setText(inv.paid > 0
            ? "A fatura é a despesa que aparece nos lançamentos; estas compras ficam agregadas nela."
            : "As compras ficam só aqui. O que entra nos lançamentos é a fatura paga, pelo extrato ou à mão.");
        Ui.visible(h.invoiceOpenPayment, inv.paymentTransactionId != null);
        Ui.visible(h.invoicePay, inv.paymentTransactionId == null);
    }

    private void renderSuggestions(Context c) {
        Ui.visible(h.suggestions, suggestionCount > 0);
        if (suggestionCount == 0) {
            return;
        }
        boolean one = suggestionCount == 1;
        SpannableStringBuilder s = new SpannableStringBuilder();
        MoneyText.strong(s, suggestionCount + " " + (one ? "possível transferência" : "possíveis transferências"));
        MoneyText.colored(s, c, " entre suas contas " + (one ? "está contando" : "estão contando") + " como despesa e receita.",
            R.color.muted_foreground);
        h.suggestionsText.setText(s);
    }

    private void renderSummary(Context c) {
        boolean show = page != null && !loading && page.invoice == null;
        Ui.visible(h.summary, show);
        if (!show) {
            return;
        }
        Summary s = page.summary;
        h.summaryCount.setText(s.count + (s.count == 1 ? " lançamento" : " lançamentos"));
        boolean all = ALL.equals(typeFilter);
        Ui.visible(h.summaryGrid, all);
        Ui.visible(h.summaryTotal, !all);
        long pendingOfType = "INCOME".equals(typeFilter) ? s.pendingIncome : "EXPENSE".equals(typeFilter) ? s.pendingExpense
            : TRANSFER.equals(typeFilter) ? s.pendingTransfer : 0;
        Ui.visible(h.summaryPending, !all && pendingOfType != 0);
        if (!all) {
            if (pendingOfType != 0) {
                SpannableStringBuilder p = new SpannableStringBuilder("pendente ");
                MoneyText.append(p, c, pendingOfType, null, TRANSFER.equals(typeFilter), true, true);
                h.summaryPending.setText(p);
            }
            if (TRANSFER.equals(typeFilter)) {
                Ui.money(h.summaryTotal, s.transfer, null, s.transfer != 0, false);
            } else {
                Ui.money(h.summaryTotal, "INCOME".equals(typeFilter) ? s.income : s.expense, typeFilter, true, false);
            }
            return;
        }
        Ui.money(h.previousBalance, s.previousBalance, null, false, false);
        Ui.money(h.transfer, s.transfer, null, s.transfer != 0, s.transfer == 0);
        forecast(h.transferForecast, s.pendingTransfer != 0, s.transfer + s.pendingTransfer, null, true);
        Ui.money(h.income, s.income, "INCOME", true, false);
        forecast(h.incomeForecast, s.pendingIncome != 0, s.income + s.pendingIncome, "INCOME", true);
        Ui.money(h.expense, s.expense, "EXPENSE", true, false);
        forecast(h.expenseForecast, s.pendingExpense != 0, s.expense + s.pendingExpense, "EXPENSE", true);
        Ui.money(h.balance, s.balance, null, false, false);
        forecast(h.balanceForecast, s.projectedBalance != s.balance, s.projectedBalance, null, false);
    }

    private void forecast(TextView tv, boolean show, long value, String type, boolean signed) {
        Ui.visible(tv, show);
        if (show) {
            SpannableStringBuilder sb = new SpannableStringBuilder("previsto ");
            MoneyText.append(sb, tv.getContext(), value, type, signed, false, true);
            tv.setText(sb);
        }
    }

    // ---------- Ações ----------

    private void openMatchSheet() {
        TransferMatchSheet.show(getParentFragmentManager());
    }

    /** Abre o formulário como despesa da fatura (na conta que paga), já ligada ao cartão e ao mês. */
    private void payInvoice() {
        CardInvoice inv = page == null ? null : page.invoice;
        if (inv == null) {
            return;
        }
        Account from = null;
        for (Account a : Catalog.get().activeAccounts()) {
            if (!"CREDIT_CARD".equals(a.type)) {
                from = a;
                break;
            }
        }
        Account card = card();
        String description = ("Fatura " + (card == null ? "" : card.name) + " "
            + Format.monthLabel(inv.month).toLowerCase(Format.PT_BR)).trim();
        TransactionFormSheet.show(getParentFragmentManager(), new AppState.FormRequest(null, null, inv.dueDate,
            from == null ? null : from.id, inv.accountId, inv.month, inv.remaining != 0 ? inv.remaining : null, description));
    }

    private void openPayment() {
        String id = page == null || page.invoice == null ? null : page.invoice.paymentTransactionId;
        if (id == null) {
            return;
        }
        for (Transaction t : page.items) {
            if (t.id.equals(id)) {
                open(t);
                return;
            }
        }
        Repo.run(getViewLifecycleOwner(), b.getRoot(), () -> TransactionsService.getTransaction(id), this::open);
    }

    @Override
    public void open(Transaction t) {
        TransactionFormSheet.show(getParentFragmentManager(), AppState.FormRequest.edit(t));
    }

    @Override
    public void togglePaid(Transaction t) {
        busy.add(t.id);
        adapter.notifyDataSetChanged();
        TxActions.togglePaid(getViewLifecycleOwner(), b.getRoot(), t, () -> busy.remove(t.id));
    }

    @Override
    public void receiptScan(Transaction t, String action) {
        busy.add(t.id);
        adapter.notifyDataSetChanged();
        TxActions.receiptScan(getViewLifecycleOwner(), b.getRoot(), t, action, () -> busy.remove(t.id));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        main.removeCallbacks(searchDebounce);
        b = null;
        h = null;
    }

    // ---------- Lista ----------

    private record Group(String date, List<Transaction> items, Long balance) {
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_DAY = 1;

    private static final class Holder extends RecyclerView.ViewHolder {
        Holder(View v) {
            super(v);
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        private List<Group> groups = new ArrayList<>();

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        void setGroups(List<Group> g) {
            groups = g;
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? TYPE_HEADER : TYPE_DAY;
        }

        @Override
        public int getItemCount() {
            return 1 + groups.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_HEADER) {
                View v = h.getRoot();
                if (v.getParent() instanceof ViewGroup p) {
                    p.removeView(v);
                }
                v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                Holder holder = new Holder(v);
                holder.setIsRecyclable(false);
                return holder;
            }
            ItemTxDayBinding day = ItemTxDayBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
            day.getRoot().setTag(day);
            return new Holder(day.getRoot());
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            if (position == 0) {
                return;
            }
            Group g = groups.get(position - 1);
            ItemTxDayBinding day = (ItemTxDayBinding) holder.itemView.getTag();
            Context c = holder.itemView.getContext();
            day.date.setText(Format.dateLong(g.date()));
            Ui.visible(day.balance, g.balance() != null);
            if (g.balance() != null) {
                SpannableStringBuilder s = new SpannableStringBuilder("Saldo ");
                MoneyText.append(s, c, g.balance(), null, false, false, true);
                day.balance.setText(s);
            }
            day.rows.removeAllViews();
            LayoutInflater inf = LayoutInflater.from(c);
            for (int i = 0; i < g.items().size(); i++) {
                Transaction t = g.items().get(i);
                if (i > 0) {
                    day.rows.addView(Views.divider(day.rows));
                }
                ItemTransactionBinding row = TransactionRowBinder.inflate(inf, day.rows);
                TransactionRowBinder.bind(row, t, false, busy.contains(t.id), TransactionsFragment.this);
                day.rows.addView(row.getRoot());
            }
        }
    }
}
