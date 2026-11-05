package br.com.contaslt.dto;

/** Porte de RecurringBill (frontend/src/api/types.ts). Dinheiro em centavos. */
public class RecurringBill {
    public String id;
    public String description;
    public long amount;
    public String type;
    public String frequency;
    public String startDate;
    public String endDate;
    public Integer occurrences;
    public String accountId;
    public String accountName;
    public String categoryId;
    public String categoryName;
    public String categoryColor;
    public String categoryIcon;
    public String notes;
    public boolean active;
    public String nextDueDate;
    public int matchStrictness;
}
