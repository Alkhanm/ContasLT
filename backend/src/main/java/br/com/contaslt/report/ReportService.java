package br.com.contaslt.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.contaslt.category.Category;
import br.com.contaslt.category.CategoryRepository;
import br.com.contaslt.common.TextNormalizer;
import br.com.contaslt.common.TransactionType;
import br.com.contaslt.recurring.RecurringBillService;
import br.com.contaslt.report.ReportDtos.CategoryReport;
import br.com.contaslt.report.ReportDtos.CategorySpending;
import br.com.contaslt.report.ReportDtos.ItemPurchase;
import br.com.contaslt.report.ReportDtos.ItemReport;
import br.com.contaslt.report.ReportDtos.ItemSpending;
import br.com.contaslt.report.ReportDtos.MonthValue;
import br.com.contaslt.transaction.Transaction;
import br.com.contaslt.transaction.TransactionItem;
import br.com.contaslt.transaction.TransactionItemRepository;
import br.com.contaslt.transaction.TransactionRepository;
import br.com.contaslt.user.User;

/** Relatórios de gasto: por categoria (expansão do painel da tela inicial) e por item de comprovante. */
@Service
public class ReportService {

    static final int HISTORY_MONTHS = 6;
    static final String UNCATEGORIZED_COLOR = "#9CA3AF";
    static final String UNCATEGORIZED_ICON = "circle-dashed";

    private final TransactionRepository transactions;
    private final TransactionItemRepository items;
    private final CategoryRepository categories;
    private final RecurringBillService recurring;

    public ReportService(TransactionRepository transactions, TransactionItemRepository items,
            CategoryRepository categories, RecurringBillService recurring) {
        this.transactions = transactions;
        this.items = items;
        this.categories = categories;
        this.recurring = recurring;
    }

    /**
     * Despesas do mês por categoria, como o painel da tela inicial (todas as contas menos cartões; pagas e
     * pendentes), mais as subcategorias, o mês anterior e os últimos {@value #HISTORY_MONTHS} meses de cada uma.
     */
    @Transactional
    public CategoryReport byCategory(User user, YearMonth month) {
        YearMonth start = month.minusMonths(HISTORY_MONTHS - 1);
        for (YearMonth ym = start; !ym.isAfter(month); ym = ym.plusMonths(1)) {
            recurring.ensureOccurrences(user, ym);
        }
        List<Transaction> all = transactions.findByUserAndPeriod(user.getId(), start.atDay(1), month.atEndOfMonth());

        Map<Long, Category> roots = new LinkedHashMap<>();
        Map<Long, Category> subs = new LinkedHashMap<>();
        // Totais por raiz e mês; a chave nula é "Sem categoria".
        Map<Long, Map<YearMonth, BigDecimal>> rootByMonth = new LinkedHashMap<>();
        Map<Long, Integer> rootCount = new LinkedHashMap<>();
        // Totais do mês por subcategoria (ou raiz, para o que foi lançado direto nela), agrupados pela raiz.
        Map<Long, Map<Long, BigDecimal>> childTotals = new LinkedHashMap<>();
        Map<Long, Map<Long, Integer>> childCounts = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal previousTotal = BigDecimal.ZERO;
        int count = 0;
        YearMonth previous = month.minusMonths(1);

        for (Transaction t : all) {
            if (t.isTransfer() || t.getType() != TransactionType.EXPENSE) {
                continue;
            }
            YearMonth ym = YearMonth.from(t.getDueDate());
            Category c = t.getCategory();
            Category root = c == null ? null : c.getRoot();
            Long rootId = root == null ? null : root.getId();
            if (root != null) {
                roots.putIfAbsent(rootId, root);
            }
            rootByMonth.computeIfAbsent(rootId, k -> new TreeMap<>()).merge(ym, t.getAmount(), BigDecimal::add);
            if (ym.equals(previous)) {
                previousTotal = previousTotal.add(t.getAmount());
            }
            if (!ym.equals(month)) {
                continue;
            }
            total = total.add(t.getAmount());
            count++;
            rootCount.merge(rootId, 1, Integer::sum);
            if (c != null) {
                if (c.getParent() != null) {
                    subs.putIfAbsent(c.getId(), c);
                }
                childTotals.computeIfAbsent(rootId, k -> new LinkedHashMap<>()).merge(c.getId(), t.getAmount(), BigDecimal::add);
                childCounts.computeIfAbsent(rootId, k -> new LinkedHashMap<>()).merge(c.getId(), 1, Integer::sum);
            }
        }

        List<CategorySpending> result = new ArrayList<>();
        for (Map.Entry<Long, Map<YearMonth, BigDecimal>> e : rootByMonth.entrySet()) {
            Long rootId = e.getKey();
            BigDecimal monthTotal = e.getValue().getOrDefault(month, BigDecimal.ZERO);
            if (monthTotal.signum() == 0) {
                continue;
            }
            Category root = roots.get(rootId);
            List<MonthValue> history = new ArrayList<>();
            for (YearMonth ym = start; !ym.isAfter(month); ym = ym.plusMonths(1)) {
                history.add(new MonthValue(ym.toString(), e.getValue().getOrDefault(ym, BigDecimal.ZERO)));
            }
            List<CategorySpending> children = new ArrayList<>();
            Map<Long, BigDecimal> ct = childTotals.getOrDefault(rootId, Map.of());
            boolean hasSubs = ct.keySet().stream().anyMatch(subs::containsKey);
            if (hasSubs) {
                for (Map.Entry<Long, BigDecimal> ce : ct.entrySet()) {
                    Category sub = subs.get(ce.getKey());
                    int n = childCounts.get(rootId).get(ce.getKey());
                    if (sub == null) {
                        children.add(new CategorySpending(rootId, "Geral", root.getColor(), root.getIcon(), ce.getValue(),
                            percent(ce.getValue(), monthTotal), n, null, List.of(), List.of()));
                    } else {
                        children.add(new CategorySpending(sub.getId(), sub.getName(), sub.getColor(), sub.getIcon(),
                            ce.getValue(), percent(ce.getValue(), monthTotal), n, null, List.of(), List.of()));
                    }
                }
                children.sort(Comparator.comparing(CategorySpending::total).reversed());
            }
            String name = root == null ? "Sem categoria" : root.getName();
            String color = root == null ? UNCATEGORIZED_COLOR : root.getColor();
            String icon = root == null ? UNCATEGORIZED_ICON : root.getIcon();
            result.add(new CategorySpending(rootId, name, color, icon, monthTotal, percent(monthTotal, total),
                rootCount.getOrDefault(rootId, 0), e.getValue().getOrDefault(previous, BigDecimal.ZERO), children, history));
        }
        result.sort(Comparator.comparing(CategorySpending::total).reversed());
        return new CategoryReport(month.toString(), total, previousTotal, count, result);
    }

    /**
     * Itens de comprovante entre dois meses (inclusive), agrupados pelo nome normalizado. Inclui compras no cartão:
     * aqui o que importa é o produto, não o caixa. {@code categoryId} filtra pela categoria do lançamento (uma
     * categoria de primeiro nível inclui as subcategorias); {@code search} filtra pelo nome do item.
     */
    @Transactional(readOnly = true)
    public ItemReport byItem(User user, YearMonth from, YearMonth to, Long categoryId, String search) {
        Set<Long> categoryIds = null;
        if (categoryId != null) {
            categoryIds = new HashSet<>();
            categoryIds.add(categoryId);
            for (Category child : categories.findByParentId(categoryId)) {
                categoryIds.add(child.getId());
            }
        }
        String needle = TextNormalizer.normalize(search);

        Map<String, List<TransactionItem>> groups = new LinkedHashMap<>();
        for (TransactionItem i : items.findByUserAndPeriod(user.getId(), from.atDay(1), to.atEndOfMonth())) {
            Transaction t = i.getTransaction();
            if (t.isTransfer() || t.getType() != TransactionType.EXPENSE) {
                continue;
            }
            if (categoryIds != null && (t.getCategory() == null || !categoryIds.contains(t.getCategory().getId()))) {
                continue;
            }
            String key = itemKey(i.getDescription());
            if (!needle.isEmpty() && !key.contains(needle)) {
                continue;
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }

        List<ItemSpending> result = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (Map.Entry<String, List<TransactionItem>> e : groups.entrySet()) {
            List<TransactionItem> list = e.getValue();
            List<ItemPurchase> purchases = new ArrayList<>();
            Map<YearMonth, BigDecimal> byMonth = new TreeMap<>();
            BigDecimal itemTotal = BigDecimal.ZERO;
            BigDecimal quantity = BigDecimal.ZERO;
            BigDecimal first = null;
            BigDecimal last = null;
            BigDecimal min = null;
            BigDecimal max = null;
            for (TransactionItem i : list) {
                Transaction t = i.getTransaction();
                BigDecimal unit = unitPrice(i);
                purchases.add(new ItemPurchase(t.getId(), t.getDueDate(), t.getMerchant(), i.getDescription(),
                    i.getQuantity(), unit, i.getTotal()));
                byMonth.merge(YearMonth.from(t.getDueDate()), i.getTotal(), BigDecimal::add);
                itemTotal = itemTotal.add(i.getTotal());
                quantity = quantity.add(i.getQuantity() == null ? BigDecimal.ONE : i.getQuantity());
                if (unit != null) {
                    first = first == null ? unit : first;
                    last = unit;
                    min = min == null || unit.compareTo(min) < 0 ? unit : min;
                    max = max == null || unit.compareTo(max) > 0 ? unit : max;
                }
            }
            TransactionItem latest = list.get(list.size() - 1);
            Category c = latest.getTransaction().getCategory();
            List<MonthValue> months = byMonth.entrySet().stream()
                .map(m -> new MonthValue(m.getKey().toString(), m.getValue())).toList();
            result.add(new ItemSpending(e.getKey(), latest.getDescription().trim(),
                c == null ? null : c.getId(), c == null ? null : categoryPath(c),
                c == null ? UNCATEGORIZED_COLOR : c.getColor(), c == null ? UNCATEGORIZED_ICON : c.getIcon(),
                list.size(), quantity, itemTotal, first, last, min, max, months, purchases));
            total = total.add(itemTotal);
            count += list.size();
        }
        result.sort(Comparator.comparing(ItemSpending::total).reversed());
        return new ItemReport(from.toString(), to.toString(), total, count, result);
    }

    /** Chave de agrupamento: o nome sem acentos, dígitos e pontuação; se não sobrar nada, o nome em minúsculas. */
    static String itemKey(String description) {
        String key = TextNormalizer.normalize(description);
        return key.isEmpty() ? description.trim().toLowerCase() : key;
    }

    /** Preço unitário informado, ou total ÷ quantidade; sem quantidade, o total (uma unidade). */
    static BigDecimal unitPrice(TransactionItem i) {
        if (i.getUnitPrice() != null && i.getUnitPrice().signum() > 0) {
            return i.getUnitPrice();
        }
        if (i.getQuantity() != null && i.getQuantity().signum() > 0) {
            return i.getTotal().divide(i.getQuantity(), 2, RoundingMode.HALF_UP);
        }
        return i.getTotal();
    }

    private static String categoryPath(Category c) {
        return c.getParent() == null ? c.getName() : c.getParent().getName() + " › " + c.getName();
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal total) {
        if (total.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }
}
