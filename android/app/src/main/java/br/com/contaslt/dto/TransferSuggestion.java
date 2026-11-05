package br.com.contaslt.dto;

/** Porte de TransferSuggestion (frontend/src/api/types.ts). Dinheiro em centavos. */
public class TransferSuggestion {
    public Transaction from;
    public Transaction to;
    public int daysApart;
    public int score;
}
