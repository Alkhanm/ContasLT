package br.com.contaslt.data.schema;

public class CategoryRec extends Rec {
    public String name;
    /** EXPENSE ou INCOME. */
    public String type;
    public String color;
    public String icon;
    public boolean archived;
    public String parentId;
}
