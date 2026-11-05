package br.com.contaslt.dto;

/** Porte de ImportPreview (frontend/src/api/types.ts). Dinheiro em centavos. */
public class ImportPreview {
    public String source;
    public String filename;
    public String accountId;
    public int total;
    public int duplicates;
    public java.util.List<PreviewRow> rows = new java.util.ArrayList<>();
}
