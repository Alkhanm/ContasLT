package br.com.contaslt.ui.views;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import br.com.contaslt.dto.Category;
import br.com.contaslt.ui.Catalog;
import br.com.contaslt.ui.Ui;

/**
 * CategorySelect.vue: em cascata, categoria de primeiro nível e, se ela tiver subcategorias, um segundo campo
 * ("Geral" = a própria categoria). {@link #value()} é a categoria ou subcategoria escolhida; null = sem categoria.
 * Com {@link #setAllowUncategorized}, a opção "Sem categoria" vira filtro (lançamentos sem categoria).
 */
public class CategorySelectView extends LinearLayout {

    private static final String NONE = "__none";
    private static final String UNCATEGORIZED = "__uncategorized";
    private static final String ALL = "__all";

    private final SelectFieldView root;
    private final SelectFieldView child;
    private String type;
    private String value;
    private boolean uncategorized;
    private boolean allowNone = true;
    private boolean allowUncategorized;
    private String noneLabel;
    private String allLabel = "Geral";
    private Consumer<String> listener;

    public CategorySelectView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        root = new SelectFieldView(context, null);
        child = new SelectFieldView(context, null);
        root.setSheetTitle("Categoria");
        child.setSheetTitle("Subcategoria");
        addView(root, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        lp.setMarginStart(Ui.dp(context, 8));
        addView(child, lp);
        root.setOnChange(k -> {
            uncategorized = UNCATEGORIZED.equals(k);
            value = NONE.equals(k) || UNCATEGORIZED.equals(k) ? null : k;
            render();
            notifyChange();
        });
        child.setOnChange(k -> {
            Category sel = Catalog.get().category(value);
            String rootId = sel == null ? null : sel.parentId != null ? sel.parentId : sel.id;
            value = ALL.equals(k) ? rootId : k;
            render();
            notifyChange();
        });
        render();
    }

    /** EXPENSE, INCOME ou null (as duas, em grupos). */
    public void setType(String t) {
        type = t;
        render();
    }

    public void setAllowNone(boolean allow, String label) {
        allowNone = allow;
        noneLabel = label;
        render();
    }

    /** Texto da opção "a própria categoria" no campo de subcategoria ("Geral" no formulário, "Todas" no filtro). */
    public void setAllLabel(String label) {
        allLabel = label;
        render();
    }

    public void setAllowUncategorized(boolean allow) {
        allowUncategorized = allow;
        render();
    }

    public void setOnChange(Consumer<String> l) {
        listener = l;
    }

    public String value() {
        return value;
    }

    public boolean uncategorized() {
        return uncategorized;
    }

    public void setValue(String id) {
        value = id;
        uncategorized = false;
        render();
    }

    public void setUncategorized(boolean u) {
        uncategorized = u;
        if (u) {
            value = null;
        }
        render();
    }

    public void refresh() {
        render();
    }

    private void notifyChange() {
        if (listener != null) {
            listener.accept(value);
        }
    }

    private void render() {
        Catalog cat = Catalog.get();
        Category sel = cat.category(value);
        String rootId = sel == null ? null : sel.parentId != null ? sel.parentId : sel.id;

        List<SelectFieldView.Option> opts = new ArrayList<>();
        if (allowNone) {
            opts.add(SelectFieldView.Option.muted(NONE, noneLabel != null ? noneLabel : "Sem categoria"));
        }
        if (allowUncategorized) {
            opts.add(SelectFieldView.Option.icon(UNCATEGORIZED, "Sem categoria", "circle-dashed", "#9CA3AF"));
        }
        if (type != null) {
            for (Category c : cat.roots(type)) {
                opts.add(SelectFieldView.Option.icon(c.id, c.name, c.icon, c.color));
            }
        } else {
            addGroup(opts, "Despesas", cat.roots("EXPENSE"));
            addGroup(opts, "Receitas", cat.roots("INCOME"));
        }
        root.setOptions(opts);
        root.setPlaceholder(noneLabel != null ? noneLabel : allowNone ? "Sem categoria" : "Escolha a categoria");
        root.setValue(uncategorized ? UNCATEGORIZED : rootId == null ? (allowNone ? NONE : null) : rootId);

        List<Category> children = new ArrayList<>();
        for (Category c : cat.childrenOf(rootId, true)) {
            if (!c.archived || c.id.equals(value)) {
                children.add(c);
            }
        }
        boolean showChild = !children.isEmpty() && !uncategorized;
        Ui.visible(child, showChild);
        if (showChild) {
            List<SelectFieldView.Option> co = new ArrayList<>();
            co.add(SelectFieldView.Option.muted(ALL, allLabel));
            for (Category c : children) {
                co.add(SelectFieldView.Option.icon(c.id, c.name, c.icon, c.color));
            }
            child.setOptions(co);
            child.setValue(sel != null && sel.parentId != null ? sel.id : ALL);
        }
    }

    private static void addGroup(List<SelectFieldView.Option> opts, String label, List<Category> items) {
        if (items.isEmpty()) {
            return;
        }
        opts.add(SelectFieldView.Option.header(label));
        for (Category c : items) {
            opts.add(SelectFieldView.Option.icon(c.id, c.name, c.icon, c.color));
        }
    }
}
