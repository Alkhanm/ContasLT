package br.com.contaslt.data.schema;

public class AccountRec extends Rec {
    public String name;
    /** CHECKING, SAVINGS, CREDIT_CARD, CASH, INVESTMENT, OTHER. */
    public String type;
    public long initialBalance;
    public String color;
    public boolean archived;
    /** Só cartão de crédito. */
    public Integer closingDay;
    public Integer dueDay;

    public boolean isCreditCard() {
        return "CREDIT_CARD".equals(type);
    }
}
