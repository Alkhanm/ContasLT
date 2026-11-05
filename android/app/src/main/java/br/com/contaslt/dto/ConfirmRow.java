package br.com.contaslt.dto;

/** Porte de ConfirmRow (frontend/src/api/types.ts). Dinheiro em centavos. */
public class ConfirmRow {
    public String date;
    public String description;
    public Long amount;
    public String type;
    public String categoryId;
    public String externalId;
    public boolean paid;
    public boolean learnRule;
    public String linkTransactionId;
    public String invoiceCardId;
    public String invoiceMonth;
}
