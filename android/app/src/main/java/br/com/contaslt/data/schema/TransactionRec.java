package br.com.contaslt.data.schema;

import java.util.ArrayList;
import java.util.List;

public class TransactionRec extends Rec {
    public String accountId;
    public String categoryId;
    public String recurringBillId;
    public String importBatchId;
    public String transferId;
    public String description;
    public long amount;
    public String type;
    public String dueDate;
    /** Ocorrência de recorrente: data prevista pela regra. */
    public String scheduledDate;
    public boolean paid;
    public String notes;
    public String externalId;
    public Integer installmentNumber;
    public String merchant;
    public String receiptFile;
    public String receiptAccessKey;
    public boolean linkedImport;
    /** PENDING, DIVERGENT ou FAILED. */
    public String receiptScanStatus;
    public Long receiptScanTotal;
    public String receiptScanDate;
    /** Pagamento de fatura: cartão e mês (AAAA-MM-01). */
    public String cardAccountId;
    public String cardInvoiceMonth;
    public List<ItemRec> items = new ArrayList<>();

    public boolean isIncome() {
        return "INCOME".equals(type);
    }

    /** Receita soma, despesa subtrai. */
    public long signed() {
        return isIncome() ? amount : -amount;
    }
}
