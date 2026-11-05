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
        /**
         * Novo lançamento já preenchido (sugestão vinda de uma notificação do banco): descrição, valor, tipo, data,
         * conta, categoria, merchant e pago. Null nos outros casos.
         */
        public br.com.contaslt.dto.TransactionInput prefill;
        /** Vencimento recorrente que este lançamento paga (salvar atualiza o vencimento em vez de criar outro). */
        public String settleOccurrenceId;
        /** Chamado depois de salvar com sucesso (ex.: marcar a sugestão da caixa "A confirmar" como confirmada). */
        public java.util.function.Consumer<br.com.contaslt.dto.Transaction> onSaved;

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

        public static FormRequest prefilled(br.com.contaslt.dto.TransactionInput in, String settleOccurrenceId,
                java.util.function.Consumer<br.com.contaslt.dto.Transaction> onSaved) {
            FormRequest r = new FormRequest(null, in.type, in.dueDate, in.accountId, null, null, null, null);
            r.prefill = in;
            r.settleOccurrenceId = settleOccurrenceId;
            r.onSaved = onSaved;
            return r;
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
