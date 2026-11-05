package br.com.contaslt.domain;

/**
 * Linha extraída de um arquivo de extrato, antes de virar lançamento (porte de ParsedRow, frontend/src/domain/ofx.ts).
 * Valor sempre positivo, em centavos. Igualdade por identidade (como os objetos do JavaScript).
 */
public final class ParsedRow {
    public final String date;
    public final String description;
    public final long amount;
    /** EXPENSE ou INCOME. */
    public final String type;
    public final String externalId;
    public final String categoryHint;

    public ParsedRow(String date, String description, long amount, String type, String externalId, String categoryHint) {
        this.date = date;
        this.description = description;
        this.amount = amount;
        this.type = type;
        this.externalId = externalId;
        this.categoryHint = categoryHint;
    }
}
