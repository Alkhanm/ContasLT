package br.com.contaslt.ui.inbox;

import java.util.ArrayList;
import java.util.List;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.snackbar.Snackbar;

import br.com.contaslt.ContasApp;
import br.com.contaslt.R;
import br.com.contaslt.databinding.FragmentInboxBinding;
import br.com.contaslt.databinding.ItemInboxBinding;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.notify.InboxItem;
import br.com.contaslt.notify.InboxService;
import br.com.contaslt.notify.InboxStore;
import br.com.contaslt.notify.NotificationProcessor;
import br.com.contaslt.ui.AppState;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Repo;
import br.com.contaslt.ui.Ui;
import br.com.contaslt.ui.Views;
import br.com.contaslt.ui.form.TransactionFormSheet;

/**
 * Caixa "A confirmar": movimentos lidos das notificações dos bancos, já com conta, categoria e vencimento recorrente
 * sugeridos. Confirmar cria o lançamento (ou paga o vencimento); Editar abre o formulário preenchido.
 */
public class InboxFragment extends Fragment {

    private FragmentInboxBinding b;
    private InboxStore store;

    private record Row(InboxItem item, InboxService.Suggestion suggestion) {
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        b = FragmentInboxBinding.inflate(inflater, container, false);
        store = NotificationProcessor.store(requireContext());
        b.grant.setOnClickListener(v -> NotificationDisclosure.show(requireContext()));
        b.settings.setOnClickListener(v -> NavHostFragment.findNavController(this).navigate(R.id.notificationSettings));
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        ((ContasApp) requireActivity().getApplication()).container().session.dataVersion
            .observe(getViewLifecycleOwner(), v -> load());
        InboxStore.pendingCount.observe(getViewLifecycleOwner(), c -> load());
    }

    @Override
    public void onResume() {
        super.onResume();
        boolean granted = NotificationManagerCompat.getEnabledListenerPackages(requireContext()).contains(requireContext().getPackageName());
        Ui.visible(b.accessCard, !granted);
        load();
    }

    private void load() {
        if (store == null || b == null) {
            return;
        }
        Repo.run(getViewLifecycleOwner(), b.getRoot(), () -> {
            List<Row> rows = new ArrayList<>();
            for (InboxItem i : store.all()) {
                if (InboxItem.PENDING.equals(i.status)) {
                    rows.add(new Row(i, InboxService.suggest(i, store.defaultAccount(i.packageName), store.defaultCard(i.packageName))));
                } else if (InboxItem.UNRECOGNIZED.equals(i.status)) {
                    rows.add(new Row(i, null));
                }
            }
            return rows;
        }, this::render);
    }

    private void render(List<Row> rows) {
        if (b == null) {
            return;
        }
        b.list.removeAllViews();
        b.unrecognized.removeAllViews();
        int pending = 0;
        int unknown = 0;
        LayoutInflater inf = getLayoutInflater();
        for (Row r : rows) {
            if (r.suggestion() == null) {
                if (unknown > 0) {
                    b.unrecognized.addView(Views.divider(b.unrecognized));
                }
                b.unrecognized.addView(unrecognizedRow(r.item()));
                unknown++;
                continue;
            }
            pending++;
            ItemInboxBinding row = ItemInboxBinding.inflate(inf, b.list, false);
            bind(row, r.item(), r.suggestion());
            b.list.addView(row.getRoot());
        }
        b.empty.removeAllViews();
        Ui.visible(b.empty, pending == 0);
        if (pending == 0) {
            b.empty.addView(Views.empty(b.empty, R.drawable.ic_inbox, "Nada a confirmar",
                "As compras, Pix e pagamentos que os apps dos bancos notificarem aparecem aqui, já preenchidos.", null, null));
        }
        Views.section(b.unrecognizedTitle, "Não reconhecidas", "Notificações dos bancos que não viraram lançamento",
            unknown > 0 ? "Limpar" : null, v -> Repo.runAction(getViewLifecycleOwner(), b.getRoot(), store::clearUnrecognized, this::load));
        Ui.visible(b.unrecognizedTitle.getRoot(), unknown > 0);
        Ui.visible(b.unrecognized, unknown > 0);
    }

    private void bind(ItemInboxBinding row, InboxItem item, InboxService.Suggestion s) {
        row.source.setText((item.appLabel != null ? item.appLabel : item.packageName) + " · "
            + ago(item.postedAt));
        row.icon.bind(s.categoryIcon, s.categoryColor);
        row.description.setText(s.input.description);
        List<String> meta = new ArrayList<>();
        meta.add(Format.dateShort(s.input.dueDate));
        meta.add(s.accountName != null ? s.accountName : "Escolha a conta");
        if (s.categoryName != null) {
            meta.add(s.categoryName);
        }
        row.meta.setText(String.join(" · ", meta));
        Ui.money(row.amount, s.input.amount, s.input.type, true, false);
        Ui.visible(row.occurrence, s.occurrenceId != null);
        if (s.occurrenceId != null) {
            row.occurrence.setText("Parece o pagamento de " + s.occurrenceDescription + " (vence " + Format.dateShort(s.occurrenceDate)
                + ", previsto " + Format.money(s.occurrenceAmount) + "). Confirmar paga esse vencimento.");
        }
        Ui.visible(row.duplicate, s.duplicateOfId != null);
        if (s.duplicateOfId != null) {
            row.duplicate.setText("Você já lançou " + s.duplicateOfDescription + " em " + Format.dateShort(s.duplicateOfDate)
                + " com o mesmo valor. Confira antes de confirmar.");
        }
        row.raw.setText(text(item));
        row.confirm.setOnClickListener(v -> confirm(item, s));
        row.edit.setOnClickListener(v -> TransactionFormSheet.show(getParentFragmentManager(),
            AppState.FormRequest.prefilled(s.input, s.occurrenceId, t -> markConfirmed(item, t))));
        row.discard.setOnClickListener(v -> Repo.runAction(getViewLifecycleOwner(), b.getRoot(),
            () -> store.setStatus(item.id, InboxItem.DISCARDED, null), () -> {
                NotificationManagerCompat.from(requireContext()).cancel(item.id, 1);
                load();
            }));
    }

    private void confirm(InboxItem item, InboxService.Suggestion s) {
        Repo.run(getViewLifecycleOwner(), b.getRoot(), () -> {
            Transaction t = InboxService.confirm(s);
            store.setStatus(item.id, InboxItem.CONFIRMED, t.id);
            return t;
        }, t -> {
            NotificationManagerCompat.from(requireContext()).cancel(item.id, 1);
            Snackbar.make(b.getRoot(), s.occurrenceId != null ? "Vencimento pago" : "Lançamento adicionado", Snackbar.LENGTH_SHORT).show();
            load();
        });
    }

    private void markConfirmed(InboxItem item, Transaction t) {
        Repo.runAction(this, null, () -> store.setStatus(item.id, InboxItem.CONFIRMED, t.id), () -> {
            if (isAdded()) {
                NotificationManagerCompat.from(requireContext()).cancel(item.id, 1);
                load();
            }
        });
    }

    private View unrecognizedRow(InboxItem item) {
        TextView tv = new TextView(requireContext(), null, 0, R.style.Text_Xs);
        int p = Ui.dp(requireContext(), 10);
        tv.setPadding(0, p, 0, p);
        tv.setText((item.appLabel != null ? item.appLabel : item.packageName) + ": " + text(item));
        return tv;
    }

    /** "agora", "há 5 min", "há 2 h", "ontem", "08 nov". */
    static String ago(long at) {
        long min = (System.currentTimeMillis() - at) / 60_000L;
        if (min < 1) {
            return "agora";
        }
        if (min < 60) {
            return "há " + min + " min";
        }
        if (min < 24 * 60) {
            return "há " + (min / 60) + " h";
        }
        java.time.LocalDate d = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        return d.equals(java.time.LocalDate.now().minusDays(1)) ? "ontem" : Format.dateShort(d.toString());
    }

    private static String text(InboxItem i) {
        return ((i.title == null ? "" : i.title + " — ") + (i.text == null ? "" : i.text)).trim();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
