package br.com.contaslt.dto;

/** Porte de ReceiptScan (frontend/src/api/types.ts). Dinheiro em centavos. */
public class ReceiptScan {
    public String receiptFile;
    public String merchant;
    public String date;
    public boolean paid;
    public Long total;
    public long itemsTotal;
    public String paymentMethod;
    public String accessKey;
    public java.util.List<ScanItem> items = new java.util.ArrayList<>();
    public String suggestedCategoryId;
    public String suggestedCategoryName;
    public String duplicateOfTransactionId;
    public String duplicateOfDescription;
    public String duplicateOfDate;
    public String recurringMatchTransactionId;
    public String recurringMatchDescription;
    public String recurringMatchDate;
    public Long recurringMatchAmount;
    public String recurringMatchCategoryId;
    public String recurringMatchAccountId;
    public Long jobId;
    public String status;
    public boolean workerOnline;
    public String error;
}
