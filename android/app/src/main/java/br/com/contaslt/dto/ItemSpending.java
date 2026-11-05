package br.com.contaslt.dto;

/** Porte de ItemSpending (frontend/src/api/types.ts). Dinheiro em centavos. */
public class ItemSpending {
    public String key;
    public String description;
    public String categoryId;
    public String categoryName;
    public String color;
    public String icon;
    public int count;
    public double quantity;
    public long total;
    public Long firstUnitPrice;
    public Long lastUnitPrice;
    public Long minUnitPrice;
    public Long maxUnitPrice;
    public java.util.List<MonthValue> months = new java.util.ArrayList<>();
    public java.util.List<ItemPurchase> purchases = new java.util.ArrayList<>();
}
