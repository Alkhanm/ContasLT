package br.com.contaslt.domain;

/**
 * Valores monetários (porte de frontend/src/domain/money.ts): centavos inteiros ({@code long}) nos registros, nas
 * contas e nos DTOs do app. A conversão de/para reais fica para quem lê ou mostra valores em reais (planilhas,
 * campos de texto).
 */
public final class Money {

    private Money() {
    }

    public static long toCents(Double reais) {
        if (reais == null || reais.isNaN() || reais.isInfinite()) {
            return 0;
        }
        return Math.round(reais * 100);
    }

    public static long toCents(double reais) {
        return toCents(Double.valueOf(reais));
    }

    public static Long toCentsOrNull(Double reais) {
        return reais == null ? null : toCents(reais);
    }

    public static double fromCents(long cents) {
        return cents / 100.0;
    }

    public static Double fromCentsOrNull(Long cents) {
        return cents == null ? null : cents / 100.0;
    }

    public static long sum(Iterable<Long> values) {
        long s = 0;
        for (Long v : values) {
            s += v;
        }
        return s;
    }
}
