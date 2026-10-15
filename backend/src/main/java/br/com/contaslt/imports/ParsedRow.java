package br.com.contaslt.imports;

import java.math.BigDecimal;
import java.time.LocalDate;

import br.com.contaslt.common.TransactionType;

/** Linha extraída de um arquivo, antes de virar lançamento. */
public record ParsedRow(LocalDate date, String description, BigDecimal amount, TransactionType type,
        String externalId, String categoryHint) {
}
