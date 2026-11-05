package br.com.contaslt.dto;

/** Porte de Transaction (frontend/src/api/types.ts). Dinheiro em centavos. */
public class Transaction {
    public String id;
    public String description;
    public long amount;
    public String type;
    public String dueDate;
    public boolean paid;
    public String notes;
    public String accountId;
    public String accountName;
    public String categoryId;
    public String categoryName;
    public String categoryColor;
    public String categoryIcon;
    public String recurringBillId;
    public Integer installmentNumber;
    public Integer installmentTotal;
    public String scheduledDate;
    public Long expectedAmount;
    public String externalId;
    public String transferId;
    public String transferPeerTransactionId;
    public String transferPeerAccountId;
    public String transferPeerAccountName;
    public String merchant;
    public String receiptFile;
    public String receiptAccessKey;
    public String importBatchId;
    public java.util.List<TransactionItem> items = new java.util.ArrayList<>();
    public String invoiceCardId;
    public String invoiceCardName;
    public String invoiceMonth;
    public CardInvoice invoice;
    public String receiptScanStatus;
    public Long receiptScanTotal;
    public String receiptScanDate;
}
