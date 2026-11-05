package br.com.contaslt.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import br.com.contaslt.data.LocalStore;
import br.com.contaslt.data.schema.CategoryRec;
import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.data.schema.ItemRec;
import br.com.contaslt.data.schema.TransactionRec;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.domain.Text;
import br.com.contaslt.dto.CategoryReport;
import br.com.contaslt.dto.CategorySpending;
import br.com.contaslt.dto.ItemPurchase;
import br.com.contaslt.dto.ItemReport;
import br.com.contaslt.dto.ItemSpending;
import br.com.contaslt.dto.MonthValue;

/**
 * Relatórios de gasto (porte de frontend/src/services/reports.ts): por categoria (expansão do painel da tela inicial)
 * e por item de comprovante.
 */
public final class ReportsService {

    private static final int HISTORY_MONTHS = 6;
    private static final int DEFAULT_ITEM_MONTHS = 12;
    private static final String UNCATEGORIZED_COLOR = "#9CA3AF";
    private static final String UNCATEGORIZED_ICON = "circle-dashed";

    private ReportsService() {
    }

    private static int cmp(String a, String b) {
        return Integer.signum(a.compareTo(b));
    }

    /** Ordem de criação (o id sequencial do backend): createdAt e, no empate, o id. */
    private static int byCreation(TransactionRec a, TransactionRec b) {
        int c = cmp(a.createdAt, b.createdAt);
        return c != 0 ? c : cmp(a.id, b.id);
    }

    private static int byDateThenCreation(TransactionRec a, TransactionRec b) {
        int c = cmp(a.dueDate, b.dueDate);
        return c != 0 ? c : byCreation(a, b);
    }

    private static long get(Map<String, Long> m, String k) {
        Long v = m.get(k);
        return v == null ? 0 : v;
    }

    private static MonthValue monthValue(String month, long total) {
        MonthValue v = new MonthValue();
        v.month = month;
        v.total = total;
        return v;
    }

    /**
     * Despesas do mês por categoria, como o painel da tela inicial (todas as contas menos cartões; pagas e
     * pendentes), mais as subcategorias, o mês anterior e os últimos {@link #HISTORY_MONTHS} meses de cada uma.
     */
    public static CategoryReport categoryReport(String month) {
        String start = Dates.plusMonthsYm(month, -(HISTORY_MONTHS - 1));
        List<String> history = Dates.monthsBetween(start, month);
        for (String ym : history) {
            RecurringService.ensureOccurrences(ym);
        }
        LocalStore s = Ctx.db();
        String from = Dates.firstDay(start);
        String to = Dates.endOfMonth(month);
        // Como findByUserAndPeriod: fora de cartão, da data mais recente para a mais antiga.
        List<TransactionRec> all = new ArrayList<>();
        for (TransactionRec t : s.list(Entity.TRANSACTION)) {
            if (t.dueDate.compareTo(from) >= 0 && t.dueDate.compareTo(to) <= 0
                && !CardsService.isCreditCard(s.get(Entity.ACCOUNT, t.accountId))) {
                all.add(t);
            }
        }
        all.sort((a, b) -> byDateThenCreation(b, a));

        // A chave nula é "Sem categoria".
        Map<String, CategoryRec> roots = new HashMap<>();
        Map<String, CategoryRec> subs = new HashMap<>();
        Map<String, Map<String, Long>> rootByMonth = new LinkedHashMap<>();
        Map<String, Integer> rootCount = new HashMap<>();
        Map<String, Map<String, Long>> childTotals = new HashMap<>();
        Map<String, Map<String, Integer>> childCounts = new HashMap<>();
        long total = 0;
        long previousTotal = 0;
        int count = 0;
        String previous = Dates.plusMonthsYm(month, -1);

        for (TransactionRec t : all) {
            if (Text.present(t.transferId) || !"EXPENSE".equals(t.type)) {
                continue;
            }
            String ym = Dates.monthOf(t.dueDate);
            CategoryRec c = s.get(Entity.CATEGORY, t.categoryId);
            CategoryRec root = c != null ? CatalogService.rootOf(c) : null;
            String rootId = root != null ? root.id : null;
            if (root != null && !roots.containsKey(rootId)) {
                roots.put(rootId, root);
            }
            Map<String, Long> byMonth = rootByMonth.get(rootId);
            if (byMonth == null) {
                byMonth = new HashMap<>();
                rootByMonth.put(rootId, byMonth);
            }
            byMonth.put(ym, get(byMonth, ym) + t.amount);
            if (ym.equals(previous)) {
                previousTotal += t.amount;
            }
            if (!ym.equals(month)) {
                continue;
            }
            total += t.amount;
            count++;
            Integer rc = rootCount.get(rootId);
            rootCount.put(rootId, (rc == null ? 0 : rc) + 1);
            if (c != null) {
                if (c.parentId != null && !subs.containsKey(c.id)) {
                    subs.put(c.id, c);
                }
                Map<String, Long> ct = childTotals.get(rootId);
                if (ct == null) {
                    ct = new LinkedHashMap<>();
                    childTotals.put(rootId, ct);
                }
                ct.put(c.id, get(ct, c.id) + t.amount);
                Map<String, Integer> cc = childCounts.get(rootId);
                if (cc == null) {
                    cc = new HashMap<>();
                    childCounts.put(rootId, cc);
                }
                Integer n = cc.get(c.id);
                cc.put(c.id, (n == null ? 0 : n) + 1);
            }
        }

        List<CategorySpending> items = new ArrayList<>();
        for (Map.Entry<String, Map<String, Long>> e : rootByMonth.entrySet()) {
            String rootId = e.getKey();
            Map<String, Long> byMonth = e.getValue();
            long monthTotal = get(byMonth, month);
            if (monthTotal == 0) {
                continue;
            }
            CategoryRec root = roots.get(rootId);
            List<MonthValue> values = new ArrayList<>();
            for (String ym : history) {
                values.add(monthValue(ym, get(byMonth, ym)));
            }
            List<CategorySpending> children = new ArrayList<>();
            Map<String, Long> ct = childTotals.get(rootId);
            if (ct == null) {
                ct = new LinkedHashMap<>();
            }
            boolean hasSubs = false;
            for (String id : ct.keySet()) {
                if (subs.containsKey(id)) {
                    hasSubs = true;
                    break;
                }
            }
            if (hasSubs && root != null) {
                for (Map.Entry<String, Long> ce : ct.entrySet()) {
                    CategoryRec sub = subs.get(ce.getKey());
                    long value = ce.getValue();
                    CategorySpending child = new CategorySpending();
                    if (sub != null) {
                        child.categoryId = sub.id;
                        child.name = sub.name;
                        child.color = sub.color;
                        child.icon = sub.icon;
                    } else {
                        child.categoryId = rootId;
                        child.name = "Geral";
                        child.color = root.color;
                        child.icon = root.icon;
                    }
                    child.total = value;
                    child.percent = DashboardService.percent(value, monthTotal);
                    child.count = childCounts.get(rootId).get(ce.getKey());
                    child.previousTotal = null;
                    children.add(child);
                }
                children.sort((a, b) -> Long.compare(b.total, a.total));
            }
            CategorySpending item = new CategorySpending();
            item.categoryId = rootId;
            item.name = root != null ? root.name : "Sem categoria";
            item.color = root != null ? root.color : UNCATEGORIZED_COLOR;
            item.icon = root != null ? root.icon : UNCATEGORIZED_ICON;
            item.total = monthTotal;
            item.percent = DashboardService.percent(monthTotal, total);
            Integer rc = rootCount.get(rootId);
            item.count = rc == null ? 0 : rc;
            item.previousTotal = get(byMonth, previous);
            item.children = children;
            item.history = values;
            items.add(item);
        }
        items.sort((a, b) -> Long.compare(b.total, a.total));
        CategoryReport r = new CategoryReport();
        r.month = month;
        r.total = total;
        r.previousTotal = previousTotal;
        r.count = count;
        r.items = items;
        return r;
    }

    private static final class Line {
        final ItemRec item;
        final TransactionRec tx;

        Line(ItemRec item, TransactionRec tx) {
            this.item = item;
            this.tx = tx;
        }
    }

    /**
     * Itens de comprovante entre dois meses (inclusive), agrupados pelo nome normalizado. Inclui compras no cartão:
     * aqui o que importa é o produto, não o caixa. {@code categoryId} filtra pela categoria do lançamento (uma
     * categoria de primeiro nível inclui as subcategorias); {@code search} filtra pelo nome do item. Sem
     * {@code from}/{@code to}: os últimos 12 meses até o mês atual.
     */
    public static ItemReport itemReport(String fromMonth, String toMonth, String categoryId, String search) {
        Ctx.validate()
            .check("from", Text.isBlank(fromMonth) || Dates.isMonth(fromMonth), "Mês inválido")
            .check("to", Text.isBlank(toMonth) || Dates.isMonth(toMonth), "Mês inválido")
            .throwIfAny();
        String end = !Text.isBlank(toMonth) ? toMonth : Dates.currentMonth();
        String start = !Text.isBlank(fromMonth) ? fromMonth : Dates.plusMonthsYm(end, -(DEFAULT_ITEM_MONTHS - 1));
        if (start.compareTo(end) > 0) {
            throw new Ctx.BusinessException("O mês inicial não pode ser depois do final");
        }

        LocalStore s = Ctx.db();
        Set<String> categoryIds = null;
        if (categoryId != null) {
            categoryIds = new HashSet<>();
            categoryIds.add(categoryId);
            for (CategoryRec c : CatalogService.childrenOf(categoryId)) {
                categoryIds.add(c.id);
            }
        }
        String needle = Text.normalize(search);
        String from = Dates.firstDay(start);
        String to = Dates.endOfMonth(end);

        Map<String, List<Line>> groups = new LinkedHashMap<>();
        // Em ordem de compra (data, lançamento, posição do item).
        List<TransactionRec> txs = new ArrayList<>();
        for (TransactionRec t : s.list(Entity.TRANSACTION)) {
            if (t.items != null && !t.items.isEmpty() && t.dueDate.compareTo(from) >= 0 && t.dueDate.compareTo(to) <= 0) {
                txs.add(t);
            }
        }
        txs.sort(ReportsService::byDateThenCreation);
        for (TransactionRec t : txs) {
            if (Text.present(t.transferId) || !"EXPENSE".equals(t.type)) {
                continue;
            }
            if (categoryIds != null && (t.categoryId == null || !categoryIds.contains(t.categoryId)
                || s.get(Entity.CATEGORY, t.categoryId) == null)) {
                continue;
            }
            for (ItemRec item : t.items) {
                String key = itemKey(item.description);
                if (!needle.isEmpty() && !key.contains(needle)) {
                    continue;
                }
                List<Line> list = groups.get(key);
                if (list == null) {
                    list = new ArrayList<>();
                    groups.put(key, list);
                }
                list.add(new Line(item, t));
            }
        }

        List<ItemSpending> items = new ArrayList<>();
        long total = 0;
        int count = 0;
        for (Map.Entry<String, List<Line>> e : groups.entrySet()) {
            List<Line> list = e.getValue();
            List<ItemPurchase> purchases = new ArrayList<>();
            Map<String, Long> byMonth = new HashMap<>();
            long itemTotal = 0;
            double quantity = 0;
            Long first = null;
            Long last = null;
            Long min = null;
            Long max = null;
            for (Line l : list) {
                long unit = unitPrice(l.item);
                ItemPurchase p = new ItemPurchase();
                p.transactionId = l.tx.id;
                p.date = l.tx.dueDate;
                p.merchant = l.tx.merchant;
                p.description = l.item.description;
                p.quantity = l.item.quantity;
                p.unitPrice = unit;
                p.total = l.item.total;
                purchases.add(p);
                String ym = Dates.monthOf(l.tx.dueDate);
                byMonth.put(ym, get(byMonth, ym) + l.item.total);
                itemTotal += l.item.total;
                quantity += l.item.quantity != null ? l.item.quantity : 1;
                if (first == null) {
                    first = unit;
                }
                last = unit;
                min = min == null || unit < min ? unit : min;
                max = max == null || unit > max ? unit : max;
            }
            Line latest = list.get(list.size() - 1);
            CategoryRec c = s.get(Entity.CATEGORY, latest.tx.categoryId);
            List<String> monthKeys = new ArrayList<>(byMonth.keySet());
            java.util.Collections.sort(monthKeys);
            List<MonthValue> months = new ArrayList<>();
            for (String m : monthKeys) {
                months.add(monthValue(m, byMonth.get(m)));
            }
            ItemSpending it = new ItemSpending();
            it.key = e.getKey();
            it.description = Text.trim(latest.item.description);
            it.categoryId = c != null ? c.id : null;
            it.categoryName = c != null ? categoryPath(c) : null;
            it.color = c != null ? c.color : UNCATEGORIZED_COLOR;
            it.icon = c != null ? c.icon : UNCATEGORIZED_ICON;
            it.count = list.size();
            it.quantity = round6(quantity);
            it.total = itemTotal;
            it.firstUnitPrice = first;
            it.lastUnitPrice = last;
            it.minUnitPrice = min;
            it.maxUnitPrice = max;
            it.months = months;
            it.purchases = purchases;
            items.add(it);
            total += itemTotal;
            count += list.size();
        }
        items.sort((a, b) -> Long.compare(b.total, a.total));
        ItemReport r = new ItemReport();
        r.from = start;
        r.to = end;
        r.total = total;
        r.count = count;
        r.items = items;
        return r;
    }

    /** Chave de agrupamento: o nome sem acentos, dígitos e pontuação; se não sobrar nada, o nome em minúsculas. */
    public static String itemKey(String description) {
        String key = Text.normalize(description);
        return key.isEmpty() ? Text.trim(description == null ? "" : description).toLowerCase(Locale.ROOT) : key;
    }

    /** Preço unitário informado, ou total ÷ quantidade (centavos, arredondado); sem quantidade, o total. */
    public static long unitPrice(ItemRec i) {
        if (i.unitPrice != null && i.unitPrice > 0) {
            return i.unitPrice;
        }
        if (i.quantity != null && i.quantity > 0) {
            return Math.round(i.total / i.quantity + 1e-9);
        }
        return i.total;
    }

    /** Soma de quantidades fracionárias sem o ruído do ponto flutuante. */
    private static double round6(double n) {
        return Math.round(n * 1e6) / 1e6;
    }

    private static String categoryPath(CategoryRec c) {
        CategoryRec parent = c.parentId != null ? Ctx.db().get(Entity.CATEGORY, c.parentId) : null;
        return parent != null ? parent.name + " › " + c.name : c.name;
    }
}
