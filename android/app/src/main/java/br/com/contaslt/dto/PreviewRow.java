package br.com.contaslt.dto;

/** Porte de PreviewRow (frontend/src/api/types.ts). Dinheiro em centavos. */
public class PreviewRow {
    public int index;
    public String date;
    public String description;
    public long amount;
    public String type;
    public String externalId;
    public boolean duplicate;
    public String suggestedCategoryId;
    public String suggestedCategoryName;
    public String suggestedCategoryColor;
    public String suggestedCategoryIcon;
    public String matchTransactionId;
    public String matchDescription;
    public String matchDate;
    public String matchAccountName;
    public int matchItemCount;
    public boolean matchHasReceipt;
    public String matchCategoryId;
    public String matchCategoryName;
    public boolean matchRecurring;
    public Long matchAmount;
    public String invoiceCardId;
    public String invoiceCardName;
    public String invoiceMonth;
    public String invoiceDueDate;
    public Long invoiceTotal;
    public Long invoicePaid;
    public boolean invoicePaymentReceived;
}
