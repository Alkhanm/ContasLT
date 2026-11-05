package br.com.contaslt.dto;

/** Porte de Dashboard (frontend/src/api/types.ts). Dinheiro em centavos. */
public class Dashboard {
    public String month;
    public long income;
    public long expense;
    public long balance;
    public long paidIncome;
    public long paidExpense;
    public long pendingIncome;
    public long pendingExpense;
    public long transfers;
    public long currentBalance;
    public java.util.List<Account> accounts = new java.util.ArrayList<>();
    public java.util.List<CategoryTotal> byCategory = new java.util.ArrayList<>();
    public java.util.List<Transaction> overdue = new java.util.ArrayList<>();
    public java.util.List<Transaction> upcoming = new java.util.ArrayList<>();
    public java.util.List<MonthTotal> history = new java.util.ArrayList<>();
}
