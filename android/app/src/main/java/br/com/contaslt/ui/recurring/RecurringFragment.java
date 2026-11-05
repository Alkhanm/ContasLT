package br.com.contaslt.ui.recurring;

import java.util.ArrayList;
import java.util.List;

import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import br.com.contaslt.ContasApp;
import br.com.contaslt.R;
import br.com.contaslt.databinding.FragmentRecurringBinding;
import br.com.contaslt.databinding.ItemRecBillBinding;
import br.com.contaslt.databinding.ViewSectionTitleBinding;
import br.com.contaslt.dto.RecurringBill;
import br.com.contaslt.dto.RecurringBillInput;
import br.com.contaslt.services.RecurringService;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Repo;
import br.com.contaslt.ui.Ui;
import br.com.contaslt.ui.Views;
import br.com.contaslt.ui.views.UiKit;

/** Contas recorrentes (RecurringPage.vue): fixas por mês, ativas e pausadas com o interruptor, e o formulário. */
public class RecurringFragment extends Fragment {

    private FragmentRecurringBinding b;
    private List<RecurringBill> bills;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        b = FragmentRecurringBinding.inflate(inflater, container, false);
        b.newBill.setOnClickListener(v -> RecurringFormSheet.show(getParentFragmentManager(), null, null));
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        ((ContasApp) requireActivity().getApplication()).container().session.dataVersion
            .observe(getViewLifecycleOwner(), v -> load());
    }

    private void load() {
        Repo.run(getViewLifecycleOwner(), b.getRoot(), RecurringService::listRecurring, list -> {
            bills = list;
            render();
        });
    }

    private void render() {
        if (b == null || bills == null) {
            return;
        }
        List<RecurringBill> active = new ArrayList<>();
        List<RecurringBill> inactive = new ArrayList<>();
        long monthlyExpense = 0;
        long monthlyIncome = 0;
        for (RecurringBill bill : bills) {
            if (bill.active) {
                active.add(bill);
                if ("MONTHLY".equals(bill.frequency)) {
                    if ("EXPENSE".equals(bill.type)) {
                        monthlyExpense += bill.amount;
                    } else if ("INCOME".equals(bill.type)) {
                        monthlyIncome += bill.amount;
                    }
                }
            } else {
                inactive.add(bill);
            }
        }
        b.summary.setVisibility(active.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        Ui.money(b.monthlyExpense, monthlyExpense, "EXPENSE", false, false);
        Ui.money(b.monthlyIncome, monthlyIncome, "INCOME", false, false);
        Ui.visible(b.incomeBox, monthlyIncome > 0);

        b.content.removeAllViews();
        LayoutInflater inf = getLayoutInflater();
        if (!active.isEmpty()) {
            addSection(inf, "Ativas", UiKit.plural(active.size(), "conta", "contas"), active, false);
        }
        if (!inactive.isEmpty()) {
            addSection(inf, "Pausadas", null, inactive, true);
        }
        if (bills.isEmpty()) {
            LinearLayout box = new LinearLayout(requireContext());
            box.setBackgroundResource(R.drawable.bg_card_dashed);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(requireContext(), 16);
            box.addView(Views.empty(box, R.drawable.ic_repeat, "Nenhuma conta recorrente",
                "Aluguel, internet, assinaturas, parcelas: cadastre uma vez e os vencimentos aparecem todo mês.",
                "Criar a primeira", v -> RecurringFormSheet.show(getParentFragmentManager(), null, null)),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            b.content.addView(box, lp);
        }
    }

    private void addSection(LayoutInflater inf, String title, String hint, List<RecurringBill> list, boolean paused) {
        ViewSectionTitleBinding t = ViewSectionTitleBinding.inflate(inf, b.content, false);
        Views.section(t, title, hint, null, null);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(requireContext(), 24);
        tlp.bottomMargin = Ui.dp(requireContext(), 12);
        b.content.addView(t.getRoot(), tlp);

        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int p = Ui.dp(requireContext(), 16);
        card.setPadding(p, 0, p, 0);
        if (paused) {
            card.setAlpha(0.8f);
        }
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                card.addView(Views.divider(card));
            }
            card.addView(row(inf, card, list.get(i), paused));
        }
        b.content.addView(card);
    }

    private View row(LayoutInflater inf, ViewGroup parent, RecurringBill bill, boolean paused) {
        ItemRecBillBinding r = ItemRecBillBinding.inflate(inf, parent, false);
        r.icon.bind(bill.categoryIcon, bill.categoryColor);
        r.description.setText(bill.description);
        if (paused) {
            r.description.setTextColor(Ui.color(requireContext(), R.color.muted_foreground));
        }
        SpannableStringBuilder meta = new SpannableStringBuilder(describe(bill));
        if (!paused && bill.nextDueDate != null) {
            meta.append(" · próximo em ");
            UiKit.colored(meta, Format.dateShort(bill.nextDueDate), Ui.color(requireContext(), R.color.foreground), true);
        }
        r.meta.setText(meta);
        Ui.money(r.amount, bill.amount, bill.type, true, paused);
        r.open.setOnClickListener(v -> RecurringFormSheet.show(getParentFragmentManager(), bill.id, null));
        r.active.setChecked(bill.active);
        r.active.setContentDescription("Ativar " + bill.description);
        r.active.setOnCheckedChangeListener((btn, checked) -> toggleActive(bill, checked));
        return r.getRoot();
    }

    static String describe(RecurringBill b) {
        StringBuilder s = new StringBuilder(Format.frequencyLabel(b.frequency));
        if (b.occurrences != null && b.occurrences > 0) {
            s.append(" · ").append(b.occurrences).append(" parcelas");
        } else if (b.endDate != null) {
            s.append(" · até ").append(Format.dateShort(b.endDate));
        }
        if (b.accountName != null) {
            s.append(" · ").append(b.accountName);
        }
        return s.toString();
    }

    private void toggleActive(RecurringBill bill, boolean value) {
        RecurringBillInput in = new RecurringBillInput();
        in.description = bill.description;
        in.amount = bill.amount;
        in.type = bill.type;
        in.frequency = bill.frequency;
        in.startDate = bill.startDate;
        in.endDate = bill.endDate;
        in.occurrences = bill.occurrences;
        in.accountId = bill.accountId;
        in.categoryId = bill.categoryId;
        in.notes = bill.notes;
        in.active = value;
        in.matchStrictness = bill.matchStrictness;
        Repo.run(getViewLifecycleOwner(), () -> RecurringService.updateRecurring(bill.id, in),
            ok -> UiKit.toast(getActivity(), value ? "Recorrência ativada" : "Recorrência pausada"),
            e -> {
                UiKit.toast(getActivity(), e.getMessage());
                render();
            });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
