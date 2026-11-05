package br.com.contaslt.dto;

/** Porte de CategorySpending (frontend/src/api/types.ts). Dinheiro em centavos. */
public class CategorySpending {
    public String categoryId;
    public String name;
    public String color;
    public String icon;
    public long total;
    public double percent;
    public int count;
    public Long previousTotal;
    public java.util.List<CategorySpending> children = new java.util.ArrayList<>();
    public java.util.List<MonthValue> history = new java.util.ArrayList<>();
}
