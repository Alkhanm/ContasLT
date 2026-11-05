package br.com.contaslt.ui.recurring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import br.com.contaslt.R;
import br.com.contaslt.data.Json;
import br.com.contaslt.databinding.SheetRecurringFormBinding;
import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.Category;
import br.com.contaslt.dto.RecurringBill;
import br.com.contaslt.dto.RecurringBillInput;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.services.RecurringService;
import br.com.contaslt.ui.Catalog;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Repo;
import br.com.contaslt.ui.Ui;
import br.com.contaslt.ui.views.SelectFieldView;
import br.com.contaslt.ui.views.UiKit;

/**
 * Formulário de conta recorrente (RecurringFormSheet.vue): nova, edição ou "Tornar recorrente" a partir de um
 * lançamento (que vira a primeira ocorrência e é vinculado ao salvar).
 */
public class RecurringFormSheet extends BottomSheetDialogFragment {

    private static final String ARG_BILL = "billId";
    private static final String ARG_FROM = "fromTransaction";
    private static final int MATCH_LEVEL_DEFAULT = 5;
    private static final String[] FREQUENCIES = {"WEEKLY", "BIWEEKLY", "MONTHLY", "BIMONTHLY", "QUARTERLY", "SEMIANNUAL", "YEARLY"};

    /** Tolerâncias por nível (espelha lib/matching.ts): % do valor (null = qualquer) e dias. */
    private static final Integer[] TOL_AMOUNT = {null, null, 75, 50, 40, 30, 20, 12, 6, 2, 0};
    private static final int[] TOL_DAYS = {0, 45, 35, 28, 21, 15, 10, 7, 4, 2, 0};

    private SheetRecurringFormBinding b;
    private String billId;
    private Transaction from;
    private RecurringBill bill;
    private boolean saving;
    private final Map<String, TextView> errorViews = new HashMap<>();

    /** Abre o formulário: {@code billId} null = nova; {@code fromTransaction} = "Tornar recorrente" (opcional). */
    public static void show(FragmentManager fm, String billId, Transaction fromTransaction) {
        RecurringFormSheet s = new RecurringFormSheet();
        Bundle args = new Bundle();
        args.putString(ARG_BILL, billId);
        if (fromTransaction != null) {
            args.putString(ARG_FROM, Json.GSON.toJson(fromTransaction));
        }
        s.setArguments(args);
        s.show(fm, "recurring-form");
    }

    public static String matchLevelHint(int level) {
        int l = Math.min(10, Math.max(1, level));
        if (l <= 1) {
            return "Só o nome, dentro do mesmo mês. Qualquer valor.";
        }
        if (l >= 10) {
            return "Nome, valor exato e data exata.";
        }
        return "Nome parecido, valor até ±" + TOL_AMOUNT[l] + "% do estimado e data até " + TOL_DAYS[l] + " dias do vencimento previsto.";
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        // Contexto da Activity: DateFieldView abre o seletor de data pelo FragmentActivity do contexto.
        b = SheetRecurringFormBinding.inflate(LayoutInflater.from(requireActivity()), container, false);
        Bundle args = requireArguments();
        billId = args.getString(ARG_BILL);
        String fromJson = args.getString(ARG_FROM);
        from = fromJson == null ? null : Json.GSON.fromJson(fromJson, Transaction.class);

        errorViews.put("amount", b.amountError);
        errorViews.put("description", b.descriptionError);
        errorViews.put("startDate", b.startDateError);
        errorViews.put("accountId", b.accountError);
        errorViews.put("occurrences", b.occurrencesError);
        errorViews.put("endDate", b.endDateError);

        b.type.setOptions(new String[] {"Despesa", "Receita"}, new String[] {"EXPENSE", "INCOME"});
        b.type.setOnChange(this::onType);
        List<SelectFieldView.Option> freq = new ArrayList<>();
        for (String f : FREQUENCIES) {
            freq.add(SelectFieldView.Option.of(f, Format.frequencyLabel(f)));
        }
        b.frequency.setOptions(freq);
        b.frequency.setSheetTitle("Repete");
        b.endMode.setOptions(new String[] {"Sem fim", "Parcelado", "Até uma data"}, new String[] {"never", "installments", "date"});
        b.endMode.setOnChange(m -> renderEndMode());
        b.endDate.setClearable(true);
        b.match.addOnChangeListener((slider, value, fromUser) -> renderMatch());
        b.close.setOnClickListener(v -> dismiss());
        b.save.setOnClickListener(v -> save());
        b.delete.setOnClickListener(v -> UiKit.confirm(requireContext(), "Excluir conta recorrente?",
            "Os vencimentos ainda não pagos serão removidos. Os já pagos ficam no histórico.", "Excluir", true, this::remove));

        if (billId != null) {
            b.title.setText("Editar conta recorrente");
            b.save.setEnabled(false);
            Repo.run(this, b.getRoot(), () -> {
                for (RecurringBill x : RecurringService.listRecurring()) {
                    if (x.id.equals(billId)) {
                        return x;
                    }
                }
                throw new br.com.contaslt.services.Ctx.NotFoundException("Conta recorrente não encontrada");
            }, x -> {
                bill = x;
                b.save.setEnabled(true);
                fill();
            });
        } else {
            b.title.setText(from != null ? "Tornar recorrente" : "Nova conta recorrente");
            fill();
        }
        return b.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null) {
            UiKit.fullSheet(getDialog(), 0.92f);
            if (getDialog().getWindow() != null) {
                getDialog().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        }
    }

    private void fill() {
        RecurringBill x = bill;
        Transaction t = from;
        String type = x != null ? x.type : t != null ? t.type : "EXPENSE";
        if (!"INCOME".equals(type)) {
            type = "EXPENSE";
        }
        UiKit.bindTypeToggle(b.type, type);
        b.category.setType(type);
        if (t != null) {
            UiKit.setEnabledDeep(b.type, false);
            b.type.setAlpha(0.6f);
            b.fromNote.setText("O lançamento de " + Format.dateShort(t.dueDate)
                + " vira a primeira ocorrência: nada é duplicado e os próximos vencimentos são gerados a partir dele.");
            Ui.visible(b.fromNote, true);
        }
        b.amount.setValue(x != null ? Long.valueOf(x.amount) : t != null ? Long.valueOf(t.amount) : null);
        b.description.setText(x != null ? x.description : t != null ? t.description : "");
        b.frequency.setValue(x != null ? x.frequency : "MONTHLY");
        b.startDate.setValue(x != null ? x.startDate : t != null ? t.dueDate : Format.todayIso());
        String mode = x != null && x.occurrences != null && x.occurrences > 0 ? "installments" : x != null && x.endDate != null ? "date" : "never";
        b.endMode.setValue(mode);
        b.occurrences.setText(String.valueOf(x != null && x.occurrences != null ? x.occurrences : 12));
        b.endDate.setValue(x != null ? x.endDate : null);
        b.category.setValue(x != null ? x.categoryId : t != null ? t.categoryId : null);
        String account = x != null ? x.accountId : t != null ? t.accountId : null;
        if (account == null) {
            List<Account> active = Catalog.get().activeAccounts();
            account = active.isEmpty() ? null : active.get(0).id;
        }
        b.account.setValue(account);
        b.account.refresh();
        b.notes.setText(x != null && x.notes != null ? x.notes : "");
        Ui.visible(b.activeBox, x != null);
        b.active.setChecked(x == null || x.active);
        int level = x != null && x.matchStrictness > 0 ? x.matchStrictness : MATCH_LEVEL_DEFAULT;
        b.match.setValue(Math.min(10, Math.max(1, level)));
        Ui.visible(b.delete, x != null);
        renderEndMode();
        renderMatch();
        renderSave();
        UiKit.showErrors(null, errorViews);
    }

    private void onType(String type) {
        UiKit.bindTypeToggle(b.type, type);
        Category c = Catalog.get().category(b.category.value());
        b.category.setType(type);
        if (c != null && !type.equals(c.type)) {
            b.category.setValue(null);
        }
    }

    private void renderEndMode() {
        String m = b.endMode.value();
        Ui.visible(b.occurrencesBox, "installments".equals(m));
        Ui.visible(b.endDateBox, "date".equals(m));
    }

    private void renderMatch() {
        int level = Math.round(b.match.getValue());
        b.matchValue.setText(level + "/10");
        b.matchHint.setText(matchLevelHint(level));
    }

    private void renderSave() {
        b.save.setText(saving ? "Salvando…" : bill != null ? "Salvar alterações" : "Criar recorrência");
        b.save.setEnabled(!saving);
    }

    private Integer occurrences() {
        try {
            String s = b.occurrences.getText().toString().trim();
            return s.isEmpty() ? null : Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean validate() {
        Map<String, String> e = new HashMap<>();
        Long amount = b.amount.value();
        String mode = b.endMode.value();
        String start = b.startDate.value();
        String end = b.endDate.value();
        if (amount == null || amount <= 0) {
            e.put("amount", "Informe um valor maior que zero");
        }
        if (b.description.getText().toString().trim().isEmpty()) {
            e.put("description", "Informe a descrição");
        }
        if (start == null) {
            e.put("startDate", "Informe o primeiro vencimento");
        }
        if (b.account.value() == null) {
            e.put("accountId", "Escolha a conta");
        }
        Integer occ = occurrences();
        if ("installments".equals(mode) && (occ == null || occ < 2)) {
            e.put("occurrences", "Pelo menos 2 parcelas");
        }
        if ("date".equals(mode) && end == null) {
            e.put("endDate", "Informe a data final");
        }
        if ("date".equals(mode) && end != null && start != null && end.compareTo(start) < 0) {
            e.put("endDate", "Deve ser depois do primeiro vencimento");
        }
        UiKit.showErrors(e, errorViews);
        b.amount.setInvalid(e.containsKey("amount"));
        return e.isEmpty();
    }

    private void save() {
        if (!validate()) {
            return;
        }
        saving = true;
        renderSave();
        String mode = b.endMode.value();
        RecurringBillInput in = new RecurringBillInput();
        in.description = b.description.getText().toString().trim();
        in.amount = b.amount.value();
        in.type = b.type.value();
        in.frequency = b.frequency.value();
        in.startDate = b.startDate.value();
        in.endDate = "date".equals(mode) ? b.endDate.value() : null;
        in.occurrences = "installments".equals(mode) ? occurrences() : null;
        in.accountId = b.account.value();
        in.categoryId = b.category.value();
        String notes = b.notes.getText().toString().trim();
        in.notes = notes.isEmpty() ? null : notes;
        in.active = bill != null ? b.active.isChecked() : Boolean.TRUE;
        in.matchStrictness = Math.round(b.match.getValue());
        in.fromTransactionId = bill == null && from != null ? from.id : null;
        boolean editing = bill != null;
        String id = editing ? bill.id : null;
        Repo.run(this, () -> editing ? RecurringService.updateRecurring(id, in) : RecurringService.createRecurring(in), ok -> {
            UiKit.toast(getActivity(), editing ? "Conta recorrente atualizada" : "Conta recorrente criada");
            dismiss();
        }, err -> {
            saving = false;
            if (b == null) {
                return;
            }
            renderSave();
            UiKit.showErrors(err.errors, errorViews);
            UiKit.snack(b.getRoot(), err.getMessage());
        });
    }

    private void remove() {
        if (bill == null) {
            return;
        }
        String id = bill.id;
        Repo.runAction(this, b.getRoot(), () -> RecurringService.deleteRecurring(id), () -> {
            UiKit.toast(getActivity(), "Conta recorrente excluída");
            dismiss();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
