package br.com.contaslt.dto;

/** Porte de ItemReport (frontend/src/api/types.ts). Dinheiro em centavos. */
public class ItemReport {
    public String from;
    public String to;
    public long total;
    public int count;
    public java.util.List<ItemSpending> items = new java.util.ArrayList<>();
}
