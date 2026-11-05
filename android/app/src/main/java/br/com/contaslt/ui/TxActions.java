package br.com.contaslt.ui;

import java.util.function.Function;

import android.view.View;

import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.snackbar.Snackbar;

import br.com.contaslt.dto.Transaction;
import br.com.contaslt.services.TransactionsService;

/** Ações rápidas de uma linha de lançamento (marcar pago, responder ao aviso de comprovante), com o aviso do PWA. */
public final class TxActions {

    /** "Ler de novo" passa pelo servidor (ReceiptsService.retryReceipt); ligado na inicialização do app. */
    public static volatile Function<String, Transaction> retryReceipt;

    private TxActions() {
    }

    public static void togglePaid(LifecycleOwner owner, View anchor, Transaction t, Runnable done) {
        Repo.run(owner, anchor, () -> TransactionsService.setPaid(t.id, !t.paid), r -> {
            Snackbar.make(anchor, t.paid ? "Marcado como pendente" : "INCOME".equals(t.type) ? "Marcado como recebido" : "Marcado como pago",
                Snackbar.LENGTH_SHORT).show();
            if (done != null) {
                done.run();
            }
        });
    }

    public static void receiptScan(LifecycleOwner owner, View anchor, Transaction t, String action, Runnable done) {
        Repo.run(owner, anchor, () -> "RETRY".equals(action) && retryReceipt != null ? retryReceipt.apply(t.id)
            : TransactionsService.resolveReceiptScan(t.id, action), r -> {
            Snackbar.make(anchor, "ACCEPT".equals(action) ? "Valor e data do comprovante aplicados"
                : "RETRY".equals(action) ? "Comprovante enviado de novo para leitura" : "Aviso removido", Snackbar.LENGTH_SHORT).show();
            if (done != null) {
                done.run();
            }
        });
    }
}
