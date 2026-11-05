package br.com.contaslt.dto;

/** Porte de ItemPurchase (frontend/src/api/types.ts). Dinheiro em centavos. */
public class ItemPurchase {
    public String transactionId;
    public String date;
    public String merchant;
    public String description;
    public Double quantity;
    public Long unitPrice;
    public long total;
}
