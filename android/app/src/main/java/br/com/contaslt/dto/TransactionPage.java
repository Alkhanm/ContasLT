package br.com.contaslt.dto;

/** Porte de TransactionPage (frontend/src/api/types.ts). Dinheiro em centavos. */
public class TransactionPage {
    public java.util.List<Transaction> items = new java.util.ArrayList<>();
    public Summary summary;
    public java.util.List<DayBalance> dayBalances = new java.util.ArrayList<>();
    public CardInvoice invoice;
}
