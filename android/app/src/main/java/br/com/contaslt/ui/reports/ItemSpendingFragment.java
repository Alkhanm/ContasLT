package br.com.contaslt.ui.reports;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import br.com.contaslt.ContasApp;
import br.com.contaslt.R;
import br.com.contaslt.databinding.FragmentItemSpendingBinding;
import br.com.contaslt.databinding.ItemRepItemBinding;
import br.com.contaslt.dto.ItemPurchase;
import br.com.contaslt.dto.ItemReport;
import br.com.contaslt.dto.ItemSpending;
import br.com.contaslt.services.CatalogService;
import br.com.contaslt.services.ReportsService;
import br.com.contaslt.ui.Catalog;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Repo;
import br.com.contaslt.ui.Ui;
import br.com.contaslt.ui.Views;
import br.com.contaslt.ui.views.MonthBarsView;
import br.com.contaslt.ui.views.PriceLineView;
import br.com.contaslt.ui.views.SelectFieldView;
import br.com.contaslt.ui.views.UiKit;

/**
 * Gasto por itens (ItemSpendingPage.vue): itens dos comprovantes no período, com busca, ordenação, filtro de
 * categoria, preço unitário ao longo das compras e gasto por mês.
 */
public class ItemSpendingFragment extends Fragment {

    private FragmentItemSpendingBinding b;
    private ItemReport data;
    private final Set<String> expanded = new HashSet<>();
    private int loadSeq;

    private record Row(ItemSpending item, Double change) {
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        b = FragmentItemSpendingBinding.inflate(inflater, container, false);
        b.summary.setBackground(UiKit.cardXl(requireContext()));
        b.list.setClipToOutline(true);
        List<SelectFieldView.Option> ranges = new ArrayList<>();
        ranges.add(SelectFieldView.Option.of("3", "Últimos 3 meses"));
        ranges.add(SelectFieldView.Option.of("6", "Últimos 6 meses"));
        ranges.add(SelectFieldView.Option.of("12", "Últimos 12 meses"));
        ranges.add(SelectFieldView.Option.of("24", "Últimos 24 meses"));
        ranges.add(SelectFieldView.Option.of("all", "Desde o início"));
        b.range.setOptions(ranges);
        b.range.setValue("12");
        b.range.setSheetTitle("Período");
        b.range.setOnChange(v -> load());
        List<SelectFieldView.Option> sorts = new ArrayList<>();
        sorts.add(SelectFieldView.Option.of("total", "Maior gasto"));
        sorts.add(SelectFieldView.Option.of("count", "Mais comprados"));
        sorts.add(SelectFieldView.Option.of("up", "Maior alta de preço"));
        sorts.add(SelectFieldView.Option.of("down", "Maior queda de preço"));
        sorts.add(SelectFieldView.Option.of("name", "Nome"));
        b.sort.setOptions(sorts);
        b.sort.setValue("total");
        b.sort.setSheetTitle("Ordenar por");
        b.sort.setOnChange(v -> render());
        b.category.setType("EXPENSE");
        b.category.setAllowNone(true, "Todas as categorias");
        b.category.setOnChange(v -> load());
        b.search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                render();
            }
        });
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        ((ContasApp) requireActivity().getApplication()).container().session.dataVersion
            .observe(getViewLifecycleOwner(), v -> load());
        Catalog.live.observe(getViewLifecycleOwner(), c -> b.category.refresh());
    }

    private void load() {
        String to = Format.currentMonth();
        String range = b.range.value();
        String from = "all".equals(range) ? "2000-01" : Format.shiftMonth(to, -(Integer.parseInt(range) - 1));
        String categoryId = b.category.value();
        int seq = ++loadSeq;
        Repo.run(getViewLifecycleOwner(), b.getRoot(), () -> ReportsService.itemReport(from, to, categoryId, null), r -> {
            if (seq != loadSeq) {
                return;
            }
            data = r;
            render();
        });
    }

    private static Double priceChange(ItemSpending item) {
        if (item.firstUnitPrice == null || item.lastUnitPrice == null || item.firstUnitPrice <= 0 || item.count < 2) {
            return null;
        }
        return (item.lastUnitPrice - item.firstUnitPrice) * 100.0 / item.firstUnitPrice;
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Format.PT_BR);
    }

    private List<Row> rows() {
        String needle = normalize(b.search.getText().toString().trim());
        List<Row> list = new ArrayList<>();
        for (ItemSpending item : data.items) {
            if (needle.isEmpty() || normalize(item.description).contains(needle) || (item.key != null && item.key.contains(needle))) {
                list.add(new Row(item, priceChange(item)));
            }
        }
        Comparator<Row> by = switch (b.sort.value()) {
            case "count" -> (x, y) -> x.item.count != y.item.count ? Integer.compare(y.item.count, x.item.count) : Long.compare(y.item.total, x.item.total);
            case "up" -> (x, y) -> Double.compare(y.change == null ? Double.NEGATIVE_INFINITY : y.change, x.change == null ? Double.NEGATIVE_INFINITY : x.change);
            case "down" -> (x, y) -> Double.compare(x.change == null ? Double.POSITIVE_INFINITY : x.change, y.change == null ? Double.POSITIVE_INFINITY : y.change);
            case "name" -> (x, y) -> CatalogService.COLLATOR.compare(x.item.description, y.item.description);
            default -> (x, y) -> Long.compare(y.item.total, x.item.total);
        };
        list.sort(by);
        return list;
    }

    private void render() {
        if (b == null || data == null) {
            return;
        }
        b.results.setVisibility(View.VISIBLE);
        List<Row> rows = rows();
        long shownTotal = 0;
        int shownCount = 0;
        for (Row r : rows) {
            shownTotal += r.item.total;
            shownCount += r.item.count;
        }
        b.summaryText.setText(UiKit.plural(rows.size(), "item", "itens") + " · " + UiKit.plural(shownCount, "compra", "compras"));
        Ui.money(b.summaryTotal, shownTotal, "EXPENSE", true, false);

        b.list.removeAllViews();
        b.empty.removeAllViews();
        Ui.visible(b.list, !rows.isEmpty());
        Ui.visible(b.empty, rows.isEmpty());
        if (rows.isEmpty()) {
            boolean any = !data.items.isEmpty();
            b.empty.addView(Views.empty(b.empty, R.drawable.ic_shopping_basket,
                any ? "Nenhum item com esse nome" : "Nenhum item no período",
                any ? "Tente outra palavra ou amplie o período." : "Os itens vêm da leitura de comprovantes: no formulário de despesa, use “Ler comprovante”.",
                null, null));
            return;
        }
        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                b.list.addView(Views.divider(b.list));
            }
            b.list.addView(row(inf, rows.get(i)));
        }
    }

    private View row(LayoutInflater inf, Row row) {
        Context c = requireContext();
        ItemSpending item = row.item;
        ItemRepItemBinding r = ItemRepItemBinding.inflate(inf, b.list, false);
        boolean open = expanded.contains(item.key);
        r.icon.bind(item.icon, item.color);
        r.icon.setContentDescription(item.categoryName != null ? item.categoryName : "Sem categoria");
        r.name.setText(item.description);
        Ui.money(r.total, item.total, null, false, false);
        String meta = (item.categoryName != null ? item.categoryName : "Sem categoria") + " · " + UiKit.plural(item.count, "compra", "compras");
        if (item.months.size() > 1) {
            meta += " em " + item.months.size() + " meses";
        }
        r.meta.setText(meta);
        Ui.visible(r.unit, item.lastUnitPrice != null);
        if (item.lastUnitPrice != null) {
            r.unit.setText(Format.money(item.lastUnitPrice) + "/un");
        }
        Double ch = row.change;
        boolean showChange = item.lastUnitPrice != null && ch != null;
        Ui.visible(r.change, showChange);
        Ui.visible(r.changeIcon, showChange);
        if (showChange) {
            int color = Ui.color(c, ch > 0 ? R.color.expense : ch < 0 ? R.color.income : R.color.muted_foreground);
            r.change.setText((ch > 0 ? "+" : "") + String.format(Format.PT_BR, "%.0f%%", ch));
            r.change.setTextColor(color);
            r.changeIcon.setImageResource(ch > 0 ? R.drawable.ic_trending_up : ch < 0 ? R.drawable.ic_trending_down : R.drawable.ic_minus);
            r.changeIcon.setImageTintList(ColorStateList.valueOf(color));
        }
        r.chevron.setRotation(open ? 180 : 0);
        r.head.setOnClickListener(v -> {
            if (!expanded.remove(item.key)) {
                expanded.add(item.key);
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

    private static String quantityLabel(Double q) {
        if (q == null) {
            return "";
        }
        if (q == Math.rint(q)) {
            return String.valueOf((long) Math.rint(q));
        }
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(Format.PT_BR);
        f.setMaximumFractionDigits(3);
        return f.format(q);
    }

    private void fillDetails(LinearLayout box, ItemSpending item) {
        Context c = requireContext();
        box.addView(UiKit.text(c, R.style.Text_Label, "Preço unitário por compra", 11));
        if (item.count > 1) {
            PriceLineView line = new PriceLineView(c);
            line.setData(item.purchases, item.color, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(c, 4);
            box.addView(line, lp);
            if (item.minUnitPrice != null && item.maxUnitPrice != null) {
                TextView t = UiKit.text(c, R.style.Text_Muted, "Mais barato " + Format.money(item.minUnitPrice) + " · mais caro "
                    + Format.money(item.maxUnitPrice) + " · " + quantityLabel(item.quantity) + " un no total", 11);
                t.setPadding(0, Ui.dp(c, 4), 0, 0);
                box.addView(t);
            }
        } else {
            TextView t = UiKit.text(c, R.style.Text_Sm, Format.money(item.lastUnitPrice) + " em uma compra só");
            t.setPadding(0, Ui.dp(c, 4), 0, 0);
            box.addView(t);
        }
        TextView monthsLabel = UiKit.text(c, R.style.Text_Label, "Gasto por mês", 11);
        monthsLabel.setPadding(0, Ui.dp(c, 16), 0, 0);
        box.addView(monthsLabel);
        MonthBarsView bars = new MonthBarsView(c);
        bars.setData(item.months, item.color, null);
        box.addView(bars);

        TextView buys = UiKit.text(c, R.style.Text_Label, "Compras", 11);
        buys.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 4));
        box.addView(buys);
        List<ItemPurchase> list = new ArrayList<>(item.purchases);
        java.util.Collections.reverse(list);
        for (int i = 0; i < list.size(); i++) {
            ItemPurchase p = list.get(i);
            if (i > 0) {
                box.addView(Views.divider(box));
            }
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));
            TextView date = UiKit.text(c, R.style.Text_Muted_Sm, Format.dateShort(p.date));
            row.addView(date, new LinearLayout.LayoutParams(Ui.dp(c, 56), ViewGroup.LayoutParams.WRAP_CONTENT));
            android.text.SpannableStringBuilder name = new android.text.SpannableStringBuilder();
            boolean hasMerchant = p.merchant != null && !p.merchant.isEmpty();
            UiKit.colored(name, hasMerchant ? p.merchant : p.description, Ui.color(c, R.color.foreground), false);
            if (hasMerchant && !p.description.equals(item.description)) {
                UiKit.colored(name, " · " + p.description, Ui.color(c, R.color.muted_foreground), false);
            }
            TextView nt = UiKit.text(c, R.style.Text_Sm, name);
            nt.setMaxLines(1);
            nt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            nlp.setMarginEnd(Ui.dp(c, 12));
            row.addView(nt, nlp);
            String qty = p.quantity != null && p.quantity != 1 ? quantityLabel(p.quantity) + " × " : "";
            TextView unit = UiKit.text(c, R.style.Text_Muted_Sm, qty + Format.moneyPlain(p.unitPrice != null ? p.unitPrice : p.total));
            row.addView(unit);
            TextView total = UiKit.text(c, R.style.Text_Semibold_Sm, null);
            Ui.money(total, p.total, null, false, false);
            total.setGravity(Gravity.END);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(Ui.dp(c, 84), ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.setMarginStart(Ui.dp(c, 12));
            row.addView(total, tlp);
            box.addView(row);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
