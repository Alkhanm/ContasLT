package br.com.contaslt.ui.views;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

import br.com.contaslt.dto.Account;
import br.com.contaslt.ui.Catalog;

/** AccountSelect.vue: contas ativas com a bolinha de cor; opcionalmente "Todas as contas". */
public class AccountSelectView extends SelectFieldView {

    private boolean allowAll;
    private Predicate<Account> filter = a -> true;

    public AccountSelectView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setPlaceholder("Escolha a conta");
        setSheetTitle("Conta");
        refresh();
    }

    public void setAllowAll(boolean allow) {
        allowAll = allow;
        refresh();
    }

    /** Ex.: só cartões, ou sem cartões. */
    public void setFilter(Predicate<Account> f) {
        filter = f;
        refresh();
    }

    /** Recarrega as opções do catálogo (chame quando as contas mudarem). */
    public void refresh() {
        List<Option> opts = new ArrayList<>();
        if (allowAll) {
            opts.add(Option.of(null, "Todas as contas"));
        }
        String current = value();
        for (Account a : Catalog.get().accounts) {
            if ((!a.archived || a.id.equals(current)) && filter.test(a)) {
                opts.add(Option.dot(a.id, a.name, a.color));
            }
        }
        setOptions(opts);
    }
}
