package br.com.contaslt.data.schema;

public class ItemRec {
    public String description;
    public Double quantity;
    public Long unitPrice;
    public long total;

    public ItemRec() {
    }

    public ItemRec(String description, Double quantity, Long unitPrice, long total) {
        this.description = description;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.total = total;
    }
}
