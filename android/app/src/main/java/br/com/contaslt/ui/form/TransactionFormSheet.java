package br.com.contaslt.ui.form;

import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import br.com.contaslt.ui.AppState;

/** Formulário de lançamento (TransactionFormSheet.vue). Ainda não portado. */
public class TransactionFormSheet extends BottomSheetDialogFragment {

    public static void show(FragmentManager fm, AppState.FormRequest request) {
        new TransactionFormSheet().show(fm, "transaction-form");
    }
}
