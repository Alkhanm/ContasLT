package br.com.contaslt.ui;

import androidx.lifecycle.MutableLiveData;

import br.com.contaslt.dto.Transaction;

/**
 * Estado de interface compartilhado (stores period e ui do PWA): mês selecionado e pedidos para abrir o formulário
 * de lançamento.
 */
public final class AppState {

    public static final MutableLiveData<String> month = new MutableLiveData<>(Format.currentMonth());

    /** Abrir o formulário: null = novo lançamento. */
    public static final class FormRequest {
        public final Transaction editing;
        public final String type;
        public final String dueDate;
        public final String accountId;
        public final String invoiceCardId;
        public final String invoiceMonth;
        public final Long invoiceAmount;
        public final String invoiceDescription;

        public FormRequest(Transaction editing, String type, String dueDate, String accountId, String invoiceCardId,
                String invoiceMonth, Long invoiceAmount, String invoiceDescription) {
            this.editing = editing;
            this.type = type;
            this.dueDate = dueDate;
            this.accountId = accountId;
            this.invoiceCardId = invoiceCardId;
            this.invoiceMonth = invoiceMonth;
            this.invoiceAmount = invoiceAmount;
            this.invoiceDescription = invoiceDescription;
        }

        public static FormRequest create() {
            return new FormRequest(null, null, null, null, null, null, null, null);
        }

        public static FormRequest edit(Transaction t) {
            return new FormRequest(t, null, null, null, null, null, null, null);
        }
    }

    private AppState() {
    }

    public static void previousMonth() {
        month.setValue(Format.shiftMonth(month.getValue(), -1));
    }

    public static void nextMonth() {
        month.setValue(Format.shiftMonth(month.getValue(), 1));
    }

    public static void thisMonth() {
        month.setValue(Format.currentMonth());
    }

    public static boolean isCurrentMonth() {
        return Format.currentMonth().equals(month.getValue());
    }
}
