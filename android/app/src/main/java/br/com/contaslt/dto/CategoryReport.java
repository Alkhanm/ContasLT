package br.com.contaslt.dto;

/** Porte de CategoryReport (frontend/src/api/types.ts). Dinheiro em centavos. */
public class CategoryReport {
    public String month;
    public long total;
    public long previousTotal;
    public int count;
    public java.util.List<CategorySpending> items = new java.util.ArrayList<>();
}
