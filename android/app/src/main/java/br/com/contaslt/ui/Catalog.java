package br.com.contaslt.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.lifecycle.MutableLiveData;

import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.Category;
import br.com.contaslt.services.CatalogService;

/**
 * Contas e categorias para formulários e filtros (store catalog do PWA). Recarregado a cada mudança nos dados
 * (MainActivity chama {@link #reload()}); leitura na thread principal pelo último valor.
 */
public final class Catalog {

    public static final MutableLiveData<Catalog> live = new MutableLiveData<>(new Catalog(Collections.emptyList(), Collections.emptyList()));

    public final List<Account> accounts;
    public final List<Category> categories;

    private Catalog(List<Account> accounts, List<Category> categories) {
        this.accounts = accounts;
        this.categories = categories;
    }

    public static Catalog get() {
        return live.getValue();
    }

    /** Fora da thread principal. */
    public static void reload() {
        live.postValue(new Catalog(CatalogService.listAccounts(true), CatalogService.listCategories(true)));
    }

    public List<Account> activeAccounts() {
        List<Account> out = new ArrayList<>();
        for (Account a : accounts) {
            if (!a.archived) {
                out.add(a);
            }
        }
        return out;
    }

    public List<Category> activeCategories(String type) {
        List<Category> out = new ArrayList<>();
        for (Category c : categories) {
            if (!c.archived && (type == null || type.equals(c.type))) {
                out.add(c);
            }
        }
        return out;
    }

    /** Categorias de primeiro nível ativas, por nome. */
    public List<Category> roots(String type) {
        List<Category> out = new ArrayList<>();
        for (Category c : activeCategories(type)) {
            if (c.parentId == null) {
                out.add(c);
            }
        }
        out.sort((a, b) -> CatalogService.COLLATOR.compare(a.name, b.name));
        return out;
    }

    public List<Category> childrenOf(String parentId, boolean includeArchived) {
        List<Category> out = new ArrayList<>();
        if (parentId == null) {
            return out;
        }
        for (Category c : categories) {
            if (parentId.equals(c.parentId) && (includeArchived || !c.archived)) {
                out.add(c);
            }
        }
        out.sort((a, b) -> CatalogService.COLLATOR.compare(a.name, b.name));
        return out;
    }

    public Account account(String id) {
        if (id == null) {
            return null;
        }
        for (Account a : accounts) {
            if (a.id.equals(id)) {
                return a;
            }
        }
        return null;
    }

    public Category category(String id) {
        if (id == null) {
            return null;
        }
        for (Category c : categories) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    /** "Pai › Filha" para subcategoria, ou só o nome. */
    public String categoryPath(String id) {
        Category c = category(id);
        if (c == null) {
            return null;
        }
        Category parent = category(c.parentId);
        return parent != null ? parent.name + " › " + c.name : c.name;
    }
}
