package br.com.contaslt.dto;

/** Porte de ImportBatch (frontend/src/api/types.ts). Dinheiro em centavos. */
public class ImportBatch {
    public String id;
    public String source;
    public String filename;
    public String accountId;
    public String accountName;
    public int importedCount;
    public String createdAt;
}
