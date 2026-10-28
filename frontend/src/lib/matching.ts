/**
 * Nível de precisão do casamento de extrato/comprovante com vencimentos de conta recorrente (1 a 10).
 * Espelha a tabela de tolerâncias do backend (OccurrenceMatcher): o nome é sempre exigido.
 */
export const MATCH_LEVEL_DEFAULT = 5

const tolerances: Record<number, { amount: number | null; days: number }> = {
  1: { amount: null, days: 45 },
  2: { amount: 75, days: 35 },
  3: { amount: 50, days: 28 },
  4: { amount: 40, days: 21 },
  5: { amount: 30, days: 15 },
  6: { amount: 20, days: 10 },
  7: { amount: 12, days: 7 },
  8: { amount: 6, days: 4 },
  9: { amount: 2, days: 2 },
  10: { amount: 0, days: 0 },
}

export function matchLevelHint(level: number): string {
  const t = tolerances[Math.min(10, Math.max(1, level))]!
  if (level <= 1) return 'Só o nome, dentro do mesmo mês. Qualquer valor.'
  if (level >= 10) return 'Nome, valor exato e data exata.'
  return `Nome parecido, valor até ±${t.amount}% do estimado e data até ${t.days} dias do vencimento previsto.`
}
