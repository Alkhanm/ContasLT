package br.com.contaslt.dto;

/** Porte de TransactionFilter (frontend/src/api/types.ts). Dinheiro em centavos. */
public class TransactionFilter {
    public String month;
    public String accountId;
    public String categoryId;
    public Boolean uncategorized;
    public String type;
    public Boolean paid;
    public String search;
    public Boolean transfers;
    public Boolean invoice;
}
