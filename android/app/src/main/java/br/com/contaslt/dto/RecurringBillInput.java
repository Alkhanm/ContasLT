package br.com.contaslt.dto;

/** Porte de RecurringBillInput (frontend/src/api/types.ts). Dinheiro em centavos. */
public class RecurringBillInput {
    public String description;
    public Long amount;
    public String type;
    public String frequency;
    public String startDate;
    public String endDate;
    public Integer occurrences;
    public String accountId;
    public String categoryId;
    public String notes;
    public Boolean active;
    public Integer matchStrictness;
    public String fromTransactionId;
}
