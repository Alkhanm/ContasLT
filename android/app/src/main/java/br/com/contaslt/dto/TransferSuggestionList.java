package br.com.contaslt.dto;

/** Porte de TransferSuggestionList (frontend/src/api/types.ts). Dinheiro em centavos. */
public class TransferSuggestionList {
    public java.util.List<TransferSuggestion> items = new java.util.ArrayList<>();
    public int rejected;
}
