package br.com.contaslt.ui.views;

import java.util.ArrayList;
import java.util.List;

import android.content.res.ColorStateList;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import br.com.contaslt.R;
import br.com.contaslt.databinding.ItemTransactionBinding;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.ui.Catalog;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Ui;

/** Preenche item_transaction.xml como o TransactionRow.vue. */
public final class TransactionRowBinder {

    public interface Listener {
        void open(Transaction t);

        void togglePaid(Transaction t);

        /** ACCEPT, DISMISS ou RETRY. */
        void receiptScan(Transaction t, String action);
    }

    private TransactionRowBinder() {
    }

    public static ItemTransactionBinding inflate(LayoutInflater inf, ViewGroup parent) {
        return ItemTransactionBinding.inflate(inf, parent, false);
    }

    public static void bind(ItemTransactionBinding b, Transaction t, boolean showDate, boolean busy, Listener l) {
        android.content.Context c = b.getRoot().getContext();
        boolean transfer = t.transferId != null;
        if (transfer) {
            b.icon.bindTransfer();
        } else {
            b.icon.bind(t.categoryIcon, t.categoryColor);
        }
        b.description.setText(t.description);
        b.description.setTextColor(Ui.color(c, t.paid ? R.color.muted_foreground : R.color.foreground));
        Ui.visible(b.recurringMark, t.recurringBillId != null);
        Ui.visible(b.invoiceMark, t.invoiceCardId != null);
        Ui.visible(b.receiptMark, t.receiptFile != null);

        List<String> parts = new ArrayList<>();
        if (showDate) {
            parts.add(Format.dateShort(t.dueDate));
        }
        if (transfer && t.transferPeerAccountName != null) {
            parts.add(("EXPENSE".equals(t.type) ? "Para " : "De ") + t.transferPeerAccountName);
        } else if (t.categoryName != null) {
            String path = Catalog.get().categoryPath(t.categoryId);
            parts.add(path != null ? path : t.categoryName);
        }
        if (t.installmentNumber != null && t.installmentTotal != null) {
            parts.add(t.installmentNumber + "/" + t.installmentTotal);
        }
        if (t.recurringBillId != null && t.scheduledDate != null && !t.scheduledDate.equals(t.dueDate)) {
            parts.add("previsto " + Format.dateShort(t.scheduledDate));
        }
        if (t.recurringBillId != null && t.expectedAmount != null && t.expectedAmount != t.amount) {
            parts.add("estimado " + Format.money(t.expectedAmount));
        }
        if (t.items != null && !t.items.isEmpty()) {
            parts.add(t.items.size() + (t.items.size() == 1 ? " item" : " itens"));
        }
        if (t.invoice != null) {
            parts.add(t.invoice.count + (t.invoice.count == 1 ? " compra" : " compras") + " no " + t.invoiceCardName);
        }
        String status = transfer && t.paid ? "Efetuada" : Format.dueLabel(t.paid, t.dueDate, t.type);
        int statusColor = switch (Format.dueStatus(t.paid, t.dueDate)) {
            case OVERDUE -> R.color.expense;
            case TODAY, SOON -> R.color.warning;
            default -> R.color.muted_foreground;
        };
        SpannableStringBuilder meta = new SpannableStringBuilder(String.join(" · ", parts));
        if (meta.length() > 0) {
            meta.append(" · ");
        }
        int start = meta.length();
        meta.append(status);
        meta.setSpan(new ForegroundColorSpan(Ui.color(c, statusColor)), start, meta.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.meta.setText(meta);

        Ui.money(b.amount, t.amount, transfer ? null : t.type, !transfer, transfer);

        b.togglePaid.setActivated(t.paid);
        b.togglePaid.setImageTintList(ColorStateList.valueOf(t.paid ? 0xFFFFFFFF : 0x00000000));
        b.togglePaid.setEnabled(!busy);
        b.togglePaid.setAlpha(busy ? 0.5f : 1f);
        boolean income = "INCOME".equals(t.type);
        b.togglePaid.setContentDescription(t.paid ? (income ? "Marcar como não recebido" : "Marcar como não pago")
            : (income ? "Marcar como recebido" : "Marcar como pago"));
        b.open.setOnClickListener(v -> l.open(t));
        b.togglePaid.setOnClickListener(v -> l.togglePaid(t));

        bindScanNotice(b, t, busy, l);
    }

    private static void bindScanNotice(ItemTransactionBinding b, Transaction t, boolean busy, Listener l) {
        String s = t.receiptScanStatus;
        Ui.visible(b.scanNotice, s != null);
        if (s == null) {
            return;
        }
        android.content.Context c = b.getRoot().getContext();
        switch (s) {
            case "PENDING" -> {
                b.scanText.setText("Comprovante aguardando leitura. Os itens entram sozinhos quando o computador com o leitor estiver ligado.");
                b.scanText.setTextColor(Ui.color(c, R.color.muted_foreground));
                Ui.visible(b.scanActions, false);
            }
            case "DIVERGENT" -> {
                List<String> read = new ArrayList<>();
                List<String> entered = new ArrayList<>();
                if (t.receiptScanTotal != null) {
                    read.add("total " + Format.money(t.receiptScanTotal));
                    entered.add(Format.money(t.amount));
                }
                if (t.receiptScanDate != null) {
                    read.add("data " + Format.dateShort(t.receiptScanDate));
                    entered.add(Format.dateShort(t.dueDate));
                }
                b.scanText.setText("Comprovante lido com diferença. O cupom diz " + String.join(" e ", read)
                    + "; você lançou " + String.join(" em ", entered) + ".");
                b.scanText.setTextColor(Ui.color(c, R.color.foreground));
                actions(b, "Usar o do comprovante", "ACCEPT", "Manter o lançado", t, busy, l);
            }
            default -> {
                b.scanText.setText("Não foi possível ler o comprovante. A foto continua anexada; tente de novo com o leitor ligado.");
                b.scanText.setTextColor(Ui.color(c, R.color.foreground));
                actions(b, "Tentar de novo", "RETRY", "Deixar assim", t, busy, l);
            }
        }
    }

    private static void actions(ItemTransactionBinding b, String primary, String primaryAction, String secondary,
            Transaction t, boolean busy, Listener l) {
        Ui.visible(b.scanActions, true);
        b.scanPrimary.setText(primary);
        b.scanSecondary.setText(secondary);
        b.scanPrimary.setEnabled(!busy);
        b.scanSecondary.setEnabled(!busy);
        b.scanPrimary.setOnClickListener(v -> l.receiptScan(t, primaryAction));
        b.scanSecondary.setOnClickListener(v -> l.receiptScan(t, "DISMISS"));
    }
}
