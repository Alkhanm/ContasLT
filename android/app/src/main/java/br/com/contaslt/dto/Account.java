package br.com.contaslt.dto;

/** Porte de Account (frontend/src/api/types.ts). Dinheiro em centavos. */
public class Account {
    public String id;
    public String name;
    public String type;
    public long initialBalance;
    public String color;
    public boolean archived;
    public long balance;
    public Integer closingDay;
    public Integer dueDay;
}
