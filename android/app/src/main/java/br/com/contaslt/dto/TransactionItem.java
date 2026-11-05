package br.com.contaslt.dto;

/** Porte de TransactionItem (frontend/src/api/types.ts). Dinheiro em centavos. */
public class TransactionItem {
    public String id;
    public String description;
    public Double quantity;
    public Long unitPrice;
    public long total;
}
