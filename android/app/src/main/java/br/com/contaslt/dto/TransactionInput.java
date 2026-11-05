package br.com.contaslt.dto;

/** Porte de TransactionInput (frontend/src/api/types.ts). Dinheiro em centavos. */
public class TransactionInput {
    public String description;
    public Long amount;
    public String type;
    public String dueDate;
    public boolean paid;
    public String notes;
    public String accountId;
    public String categoryId;
    public String merchant;
    public String receiptFile;
    public String receiptAccessKey;
    public java.util.List<TransactionItemInput> items = new java.util.ArrayList<>();
    public String invoiceCardId;
    public String invoiceMonth;
}
