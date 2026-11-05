package br.com.contaslt.dto;

/** Porte de TransferInput (frontend/src/api/types.ts). Dinheiro em centavos. */
public class TransferInput {
    public String fromAccountId;
    public String toAccountId;
    public Long amount;
    public String date;
    public String description;
    public Boolean paid;
}
