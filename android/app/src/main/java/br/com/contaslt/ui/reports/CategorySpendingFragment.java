package br.com.contaslt.ui.reports;

import java.util.HashSet;
import java.util.Set;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import br.com.contaslt.ContasApp;
import br.com.contaslt.R;
import br.com.contaslt.databinding.FragmentCategorySpendingBinding;
import br.com.contaslt.databinding.ItemRepCategoryBinding;
import br.com.contaslt.dto.CategoryReport;
import br.com.contaslt.dto.CategorySpending;
import br.com.contaslt.services.ReportsService;
import br.com.contaslt.ui.AppState;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Repo;
import br.com.contaslt.ui.Ui;
import br.com.contaslt.ui.Views;
import br.com.contaslt.ui.transactions.TransactionsFragment;
import br.com.contaslt.ui.views.CategoryIconView;
import br.com.contaslt.ui.views.MonthBarsView;
import br.com.contaslt.ui.views.UiKit;

/**
 * Gastos por categoria (CategorySpendingPage.vue): total do mês com a comparação ao anterior, participação de cada
 * categoria e, ao expandir, subcategorias, os últimos 6 meses e o atalho para os lançamentos.
 */
public class CategorySpendingFragment extends Fragment {

    private FragmentCategorySpendingBinding b;
    private CategoryReport data;
    private final Set<String> expanded = new HashSet<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        b = FragmentCategorySpendingBinding.inflate(inflater, container, false);
        b.list.setClipToOutline(true);
        b.shares.setBackground(UiKit.box(requireContext(), Ui.color(requireContext(), R.color.muted), 0, 6, 0));
        b.shares.setClipToOutline(true);
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
        Repo.run(getViewLifecycleOwner(), b.getRoot(), () -> ReportsService.categoryReport(month), r -> {
            data = r;
            render();
        });
    }

    /** Variação em % em relação ao mês anterior, ou null sem base de comparação. */
    private static Double change(long current, Long previous) {
        if (previous == null || previous <= 0) {
            return null;
        }
        return (current - previous) * 100.0 / previous;
    }

    private static String keyOf(CategorySpending item) {
        return item.categoryId == null ? "none" : item.categoryId;
    }

    private void render() {
        if (b == null || data == null) {
            return;
        }
        Context c = requireContext();
        CategoryReport d = data;
        b.content.setVisibility(View.VISIBLE);
        b.totalLabel.setText("Despesas de " + Format.monthLabel(AppState.month.getValue()));
        Ui.money(b.total, d.total, "EXPENSE", false, false);
        b.countText.setText(UiKit.plural(d.count, "lançamento", "lançamentos") + " em "
            + UiKit.plural(d.items.size(), "categoria", "categorias") + ", pagos e pendentes");
        SpannableStringBuilder prev = new SpannableStringBuilder("Mês anterior ");
        UiKit.colored(prev, Format.money(d.previousTotal), Ui.color(c, R.color.foreground), true);
        b.previous.setText(prev);
        Double total = change(d.total, d.previousTotal);
        Ui.visible(b.change, total != null);
        Ui.visible(b.changeIcon, total != null && Math.round(total) != 0);
        if (total != null) {
            int color = Ui.color(c, total > 0 ? R.color.expense : total < 0 ? R.color.income : R.color.muted_foreground);
            b.change.setText((total > 0 ? "+" : "") + String.format(Format.PT_BR, "%.0f%%", total));
            b.change.setTextColor(color);
            b.changeIcon.setImageResource(total > 0 ? R.drawable.ic_trending_up : R.drawable.ic_trending_down);
            b.changeIcon.setImageTintList(ColorStateList.valueOf(color));
        }

        b.shares.removeAllViews();
        Ui.visible(b.shares, !d.items.isEmpty());
        for (int i = 0; i < d.items.size(); i++) {
            CategorySpending item = d.items.get(i);
            View seg = new View(c);
            seg.setBackgroundColor(Ui.parseColor(item.color, Ui.color(c, R.color.primary)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) Math.max(0.0001, item.percent));
            if (i > 0) {
                lp.setMarginStart(1);
            }
            b.shares.addView(seg, lp);
        }
        float rest = 100f;
        for (CategorySpending item : d.items) {
            rest -= (float) item.percent;
        }
        if (rest > 0.5f && !d.items.isEmpty()) {
            b.shares.addView(new View(c), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, rest));
        }

        b.list.removeAllViews();
        b.empty.removeAllViews();
        Ui.visible(b.list, !d.items.isEmpty());
        Ui.visible(b.empty, d.items.isEmpty());
        if (d.items.isEmpty()) {
            b.empty.addView(Views.empty(b.empty, R.drawable.ic_chart_pie, "Sem despesas neste mês",
                "Adicione uma despesa ou importe o extrato do banco.", "Importar extrato",
                v -> NavHostFragment.findNavController(this).navigate(R.id.importStatement)));
            return;
        }
        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < d.items.size(); i++) {
            if (i > 0) {
                b.list.addView(Views.divider(b.list));
            }
            b.list.addView(row(inf, d.items.get(i)));
        }
    }

    private View row(LayoutInflater inf, CategorySpending item) {
        Context c = requireContext();
        ItemRepCategoryBinding r = ItemRepCategoryBinding.inflate(inf, b.list, false);
        String key = keyOf(item);
        boolean open = expanded.contains(key);
        r.icon.bind(item.icon, item.color);
        r.name.setText(item.name);
        Ui.money(r.total, item.total, null, false, false);
        tintBar(r.bar, item.percent, item.color);
        r.percent.setText(String.format(Format.PT_BR, "%.0f%%", item.percent));
        r.count.setText(UiKit.plural(item.count, "lançamento", "lançamentos"));
        Double delta = change(item.total, item.previousTotal);
        if (delta != null && delta == 0) {
            r.delta.setText("igual ao mês anterior");
        } else if (delta != null) {
            r.delta.setText((delta > 0 ? "+" : "") + String.format(Format.PT_BR, "%.0f%%", delta) + " vs. mês anterior");
            r.delta.setTextColor(Ui.color(c, delta > 0 ? R.color.expense : R.color.income));
            r.delta.setTypeface(r.delta.getTypeface(), android.graphics.Typeface.BOLD);
        } else {
            r.delta.setText("sem gasto no mês anterior");
        }
        r.chevron.setRotation(open ? 180 : 0);
        r.head.setOnClickListener(v -> {
            if (!expanded.remove(key)) {
                expanded.add(key);
            }
            render();
        });
        Ui.visible(r.details, open);
        if (open) {
            r.details.setBackgroundColor(UiKit.alpha(Ui.color(c, R.color.muted), 0.3f));
            r.getRoot().addView(Views.divider(r.getRoot()), 1);
            fillDetails(r.details, item);
        }
        return r.getRoot();
    }

    private void fillDetails(LinearLayout box, CategorySpending item) {
        Context c = requireContext();
        if (!item.children.isEmpty()) {
            box.addView(UiKit.text(c, R.style.Text_Label, "Subcategorias", 11));
            for (int i = 0; i < item.children.size(); i++) {
                CategorySpending child = item.children.get(i);
                if (i > 0) {
                    box.addView(Views.divider(box));
                }
                LinearLayout row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));
                CategoryIconView icon = new CategoryIconView(c);
                icon.setSize("sm");
                icon.bind(child.icon, child.color);
                row.addView(icon);
                LinearLayout col = new LinearLayout(c);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout top = new LinearLayout(c);
                TextView name = UiKit.text(c, R.style.Text_Semibold_Sm, child.name);
                name.setMaxLines(1);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView total = UiKit.text(c, R.style.Text_Semibold_Sm, null);
                Ui.money(total, child.total, null, false, false);
                top.addView(total);
                col.addView(top);
                LinearLayout barRow = new LinearLayout(c);
                barRow.setGravity(Gravity.CENTER_VERTICAL);
                ProgressBar bar = new ProgressBar(c, null, 0, androidx.appcompat.R.style.Widget_AppCompat_ProgressBar_Horizontal);
                bar.setMax(1000);
                bar.setProgressDrawable(androidx.core.content.ContextCompat.getDrawable(c, R.drawable.bar_progress));
                tintBar(bar, child.percent, child.color);
                barRow.addView(bar, new LinearLayout.LayoutParams(0, Ui.dp(c, 4), 1));
                TextView pct = UiKit.text(c, R.style.Text_Muted, String.format(Format.PT_BR, "%.0f%%", child.percent), 11);
                pct.setGravity(Gravity.END);
                barRow.addView(pct, new LinearLayout.LayoutParams(Ui.dp(c, 40), ViewGroup.LayoutParams.WRAP_CONTENT));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.topMargin = Ui.dp(c, 4);
                col.addView(barRow, blp);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                clp.setMarginStart(Ui.dp(c, 10));
                row.addView(col, clp);
                row.setOnClickListener(v -> openTransactions(child.categoryId));
                box.addView(row);
            }
            View spacer = new View(c);
            box.addView(spacer, new LinearLayout.LayoutParams(1, Ui.dp(c, 12)));
        }
        box.addView(UiKit.text(c, R.style.Text_Label, "Últimos 6 meses", 11));
        MonthBarsView bars = new MonthBarsView(c);
        bars.setData(item.history, item.color, AppState.month.getValue());
        bars.setOnSelect(m -> AppState.month.setValue(m));
        box.addView(bars);
        LinearLayout link = new LinearLayout(c);
        link.setGravity(Gravity.CENTER_VERTICAL);
        TextView lt = UiKit.text(c, R.style.Text_Semibold, "Ver lançamentos de " + item.name, 12);
        lt.setTextColor(Ui.color(c, R.color.primary));
        link.addView(lt);
        ImageView chev = new ImageView(c);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setImageTintList(ColorStateList.valueOf(Ui.color(c, R.color.primary)));
        link.addView(chev, new LinearLayout.LayoutParams(Ui.dp(c, 14), Ui.dp(c, 14)));
        link.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 4));
        link.setOnClickListener(v -> openTransactions(item.categoryId));
        box.addView(link, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    static void tintBar(ProgressBar bar, double percent, String color) {
        bar.setProgress((int) Math.round(Math.max(2, percent) * 10));
        bar.getProgressDrawable().mutate();
        LayerDrawable ld = (LayerDrawable) bar.getProgressDrawable();
        ld.findDrawableByLayerId(android.R.id.progress).setTint(Ui.parseColor(color, Ui.color(bar.getContext(), R.color.primary)));
    }

    private void openTransactions(String categoryId) {
        NavHostFragment.findNavController(this).navigate(R.id.transactions,
            TransactionsFragment.args(categoryId == null ? "nenhuma" : categoryId, "EXPENSE", null));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
