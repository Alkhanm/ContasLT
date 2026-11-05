package br.com.contaslt.dto;

/** Porte de ImportConfirmRequest (frontend/src/api/types.ts). Dinheiro em centavos. */
public class ImportConfirmRequest {
    public String accountId;
    public String source;
    public String filename;
    public java.util.List<ConfirmRow> rows = new java.util.ArrayList<>();
}
