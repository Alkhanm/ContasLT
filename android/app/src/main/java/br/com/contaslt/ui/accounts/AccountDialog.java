package br.com.contaslt.ui.accounts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import br.com.contaslt.databinding.DialogAccountBinding;
import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.AccountInput;
import br.com.contaslt.services.CatalogService;
import br.com.contaslt.ui.Catalog;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Repo;
import br.com.contaslt.ui.Ui;
import br.com.contaslt.ui.views.ColorPaletteView;
import br.com.contaslt.ui.views.SelectFieldView;
import br.com.contaslt.ui.views.UiKit;

/** AccountDialog.vue: nome, tipo, saldo inicial, cor e, para cartão, dias de fechamento e vencimento. */
public class AccountDialog extends DialogFragment {

    private static final String ARG_ID = "accountId";
    private static final String[] TYPES = {"CHECKING", "SAVINGS", "CREDIT_CARD", "CASH", "INVESTMENT", "OTHER"};

    private DialogAccountBinding b;
    private Account account;
    private boolean saving;
    private final Map<String, TextView> errorViews = new HashMap<>();

    public static void open(FragmentManager fm, String accountId) {
        AccountDialog d = new AccountDialog();
        Bundle args = new Bundle();
        args.putString(ARG_ID, accountId);
        d.setArguments(args);
        d.show(fm, "account-dialog");
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle state) {
        AppCompatDialog d = new AppCompatDialog(requireContext());
        d.supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        return d;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null) {
            UiKit.cardDialogWindow(getDialog());
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        b = DialogAccountBinding.inflate(inflater, container, false);
        account = Catalog.get().account(requireArguments().getString(ARG_ID));
        errorViews.put("name", b.nameError);
        errorViews.put("closingDay", b.closingDayError);
        errorViews.put("dueDay", b.dueDayError);

        List<SelectFieldView.Option> types = new ArrayList<>();
        for (String t : TYPES) {
            types.add(SelectFieldView.Option.of(t, Format.accountTypeLabel(t)));
        }
        b.type.setOptions(types);
        b.type.setSheetTitle("Tipo");
        b.type.setOnChange(t -> renderType());

        b.title.setText(account != null ? "Editar conta" : "Nova conta");
        b.name.setText(account != null ? account.name : "");
        b.type.setValue(account != null ? account.type : "CHECKING");
        b.initialBalance.setValue(account != null ? Long.valueOf(account.initialBalance) : null);
        b.color.setValue(account != null && account.color != null ? account.color : ColorPaletteView.PALETTE[0]);
        b.closingDay.setText(account != null && account.closingDay != null ? String.valueOf(account.closingDay) : "");
        b.dueDay.setText(account != null && account.dueDay != null ? String.valueOf(account.dueDay) : "");
        renderType();
        b.close.setOnClickListener(v -> dismiss());
        b.save.setOnClickListener(v -> save());
        return b.getRoot();
    }

    private boolean isCard() {
        return "CREDIT_CARD".equals(b.type.value());
    }

    private void renderType() {
        Ui.visible(b.cardBox, isCard());
        b.balanceHint.setText(isCard()
            ? "Negativo se já havia fatura em aberto antes do primeiro lançamento registrado aqui."
            : "Saldo da conta antes do primeiro lançamento registrado aqui.");
    }

    /** Dia digitado limitado a 1–28; vazio = null. */
    private static Integer day(TextView tv) {
        String s = tv.getText().toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Math.min(28, Math.max(1, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void save() {
        Map<String, String> e = new HashMap<>();
        String name = b.name.getText().toString().trim();
        Integer closing = day(b.closingDay);
        Integer due = day(b.dueDay);
        if (name.isEmpty()) {
            e.put("name", "Informe o nome");
        }
        if (isCard() && (closing == null) != (due == null)) {
            e.put("closingDay", "Informe os dois dias, ou nenhum");
        }
        if (isCard() && closing != null && closing.equals(due)) {
            e.put("dueDay", "Precisa ser diferente do fechamento");
        }
        UiKit.showErrors(e, errorViews);
        if (!e.isEmpty() || saving) {
            return;
        }
        saving = true;
        b.save.setText("Salvando…");
        b.save.setEnabled(false);
        AccountInput in = new AccountInput();
        in.name = name;
        in.type = b.type.value();
        in.initialBalance = b.initialBalance.value() == null ? 0 : b.initialBalance.value();
        in.color = b.color.value();
        in.closingDay = isCard() ? closing : null;
        in.dueDay = isCard() ? due : null;
        Account editing = account;
        if (editing != null) {
            in.archived = editing.archived;
        }
        Repo.run(this, () -> editing != null ? CatalogService.updateAccount(editing.id, in) : CatalogService.createAccount(in), ok -> {
            UiKit.toast(getActivity(), editing != null ? "Conta atualizada" : "Conta criada");
            dismiss();
        }, err -> {
            saving = false;
            if (b == null) {
                return;
            }
            b.save.setText("Salvar");
            b.save.setEnabled(true);
            UiKit.showErrors(err.errors, errorViews);
            UiKit.snack(b.getRoot(), err.getMessage());
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
