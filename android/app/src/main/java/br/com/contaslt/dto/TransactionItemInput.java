package br.com.contaslt.dto;

/** Porte de TransactionItemInput (frontend/src/api/types.ts). Dinheiro em centavos. */
public class TransactionItemInput {
    public String description;
    public Double quantity;
    public Long unitPrice;
    public Long total;
}
