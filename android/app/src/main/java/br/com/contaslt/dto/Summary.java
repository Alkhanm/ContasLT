package br.com.contaslt.dto;

/** Porte de Summary (frontend/src/api/types.ts). Dinheiro em centavos. */
public class Summary {
    public long previousBalance;
    public long transfer;
    public long income;
    public long expense;
    public long balance;
    public long pendingTransfer;
    public long pendingIncome;
    public long pendingExpense;
    public long projectedBalance;
    public int count;
}
