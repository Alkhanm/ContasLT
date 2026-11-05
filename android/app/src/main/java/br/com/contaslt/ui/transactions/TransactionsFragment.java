package br.com.contaslt.ui.transactions;

import android.os.Bundle;

import androidx.fragment.app.Fragment;

/** Lançamentos (TransactionsPage.vue). Ainda não portado. */
public class TransactionsFragment extends Fragment {

    /** Filtros iniciais vindos de outras telas: categoria (id ou "nenhuma"), tipo (EXPENSE, INCOME, TRANSFER) e status ("pendente"). */
    public static Bundle args(String categoria, String tipo, String status) {
        Bundle b = new Bundle();
        b.putString("categoria", categoria);
        b.putString("tipo", tipo);
        b.putString("status", status);
        return b;
    }
}
