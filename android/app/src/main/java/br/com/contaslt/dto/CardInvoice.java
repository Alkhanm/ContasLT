package br.com.contaslt.dto;

/** Porte de CardInvoice (frontend/src/api/types.ts). Dinheiro em centavos. */
public class CardInvoice {
    public String accountId;
    public String accountName;
    public String month;
    public String start;
    public String end;
    public String dueDate;
    public boolean configured;
    public long purchases;
    public int count;
    public long paid;
    public long undetailed;
    public long remaining;
    public String status;
    public String paymentTransactionId;
}
