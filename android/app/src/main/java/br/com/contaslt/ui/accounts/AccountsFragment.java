package br.com.contaslt.ui.accounts;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import br.com.contaslt.R;
import br.com.contaslt.databinding.FragmentAccountsBinding;
import br.com.contaslt.databinding.ItemAccRowBinding;
import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.AccountInput;
import br.com.contaslt.services.CatalogService;
import br.com.contaslt.ui.Catalog;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Repo;
import br.com.contaslt.ui.Ui;
import br.com.contaslt.ui.views.UiKit;

/** Contas (AccountsPage.vue): saldo total, lista com arquivar/restaurar e excluir, e o diálogo de conta. */
public class AccountsFragment extends Fragment {

    private FragmentAccountsBinding b;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        b = FragmentAccountsBinding.inflate(inflater, container, false);
        b.newAccount.setOnClickListener(v -> AccountDialog.open(getParentFragmentManager(), null));
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        Catalog.live.observe(getViewLifecycleOwner(), c -> render(c));
    }

    private void render(Catalog cat) {
        if (b == null) {
            return;
        }
        long total = 0;
        for (Account a : cat.activeAccounts()) {
            total += a.balance;
        }
        Ui.money(b.total, total, null, false, false);
        b.list.removeAllViews();
        LayoutInflater inf = getLayoutInflater();
        for (Account a : cat.accounts) {
            ItemAccRowBinding r = ItemAccRowBinding.inflate(inf, b.list, false);
            r.getRoot().setBackground(UiKit.cardXl(requireContext()));
            boolean card = "CREDIT_CARD".equals(a.type);
            r.icon.bind(card ? "credit-card" : "wallet", a.color);
            r.name.setText(a.name);
            StringBuilder meta = new StringBuilder(Format.accountTypeLabel(a.type));
            if (card && a.closingDay != null) {
                meta.append(" · fecha dia ").append(a.closingDay).append(", vence dia ").append(a.dueDay);
            }
            if (a.archived) {
                meta.append(" · arquivada");
            }
            r.meta.setText(meta);
            Ui.money(r.balance, a.balance, null, false, false);
            r.open.setOnClickListener(v -> AccountDialog.open(getParentFragmentManager(), a.id));
            if (a.archived) {
                r.open.setAlpha(0.6f);
            }
            r.actions.addView(UiKit.iconButton(requireContext(), a.archived ? R.drawable.ic_archive_restore : R.drawable.ic_archive,
                R.color.foreground, a.archived ? "Restaurar" : "Arquivar", v -> toggleArchive(a)));
            r.actions.addView(UiKit.iconButton(requireContext(), R.drawable.ic_trash_2, R.color.muted_foreground, "Excluir",
                v -> UiKit.confirm(requireContext(), "Excluir conta?",
                    "Só é possível excluir contas sem lançamentos. Se ela já foi usada, arquive-a.", "Excluir", true, () -> remove(a))));
            b.list.addView(r.getRoot());
        }
    }

    private void toggleArchive(Account a) {
        AccountInput in = new AccountInput();
        in.name = a.name;
        in.type = a.type;
        in.initialBalance = a.initialBalance;
        in.color = a.color;
        in.archived = !a.archived;
        in.closingDay = a.closingDay;
        in.dueDay = a.dueDay;
        Repo.run(getViewLifecycleOwner(), b.getRoot(), () -> CatalogService.updateAccount(a.id, in),
            ok -> UiKit.toast(getActivity(), a.archived ? "Conta restaurada" : "Conta arquivada"));
    }

    private void remove(Account a) {
        Repo.runAction(getViewLifecycleOwner(), b.getRoot(), () -> CatalogService.deleteAccount(a.id),
            () -> UiKit.toast(getActivity(), "Conta excluída"));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
