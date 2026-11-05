package br.com.contaslt.dto;

/** Porte de ImportResult (frontend/src/api/types.ts). Dinheiro em centavos. */
public class ImportResult {
    public String batchId;
    public int imported;
    public int skipped;
    public int linked;
    public int invoicePayments;
}
