package br.com.contaslt.dto;

/** Porte de BackupRestored (frontend/src/api/types.ts). Dinheiro em centavos. */
public class BackupRestored {
    public int accounts;
    public int categories;
    public int categoryRules;
    public int recurringBills;
    public int transactions;
    public int skippedTransactions;
    public int transfers;
    public int receipts;
}
