package br.com.contaslt.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ReportDtos {

    private ReportDtos() {
    }

    public record MonthValue(String month, BigDecimal total) {
    }

    /**
     * Gasto de uma categoria no mês. Em categorias de primeiro nível, {@code children} traz as subcategorias com gasto
     * (e "Geral" para o que foi lançado direto na categoria, quando há subcategorias) e {@code history} o total dos
     * últimos meses; {@code previousTotal} é o total do mês anterior.
     */
    public record CategorySpending(Long categoryId, String name, String color, String icon, BigDecimal total,
            BigDecimal percent, int count, BigDecimal previousTotal, List<CategorySpending> children,
            List<MonthValue> history) {
    }

    public record CategoryReport(String month, BigDecimal total, BigDecimal previousTotal, int count,
            List<CategorySpending> items) {
    }

    /** Uma compra de um item: a linha do comprovante e o lançamento a que pertence. */
    public record ItemPurchase(Long transactionId, LocalDate date, String merchant, String description,
            BigDecimal quantity, BigDecimal unitPrice, BigDecimal total) {
    }

    /**
     * Um item (produto) agregado pelas compras do período: categoria da compra mais recente, totais por mês e
     * evolução do preço unitário (primeiro, último, menor e maior).
     */
    public record ItemSpending(String key, String description, Long categoryId, String categoryName, String color,
            String icon, int count, BigDecimal quantity, BigDecimal total, BigDecimal firstUnitPrice,
            BigDecimal lastUnitPrice, BigDecimal minUnitPrice, BigDecimal maxUnitPrice, List<MonthValue> months,
            List<ItemPurchase> purchases) {
    }

    public record ItemReport(String from, String to, BigDecimal total, int count, List<ItemSpending> items) {
    }
}
