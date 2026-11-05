package br.com.contaslt.dto;

/** Porte de AccountInput (frontend/src/api/types.ts). Dinheiro em centavos. */
public class AccountInput {
    public String name;
    public String type;
    public long initialBalance;
    public String color;
    public Boolean archived;
    public Integer closingDay;
    public Integer dueDay;
}
