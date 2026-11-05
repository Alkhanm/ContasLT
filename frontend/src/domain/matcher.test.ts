import { describe, expect, it } from 'vitest'
import { amountDiffPercent, keyTokens, score, tokens, toleranceFor } from './matcher'

/** Pontuação do OccurrenceMatcher (parte de RecurringMatchTest que não depende do importador). */

const bill = (matchStrictness: number) => ({ type: 'EXPENSE' as const, matchStrictness })

describe('OccurrenceMatcher', () => {
  it('palavras significativas ignoram termos bancários, acentos e palavras curtas', () => {
    expect([...tokens('Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA')].sort()).toEqual(['linq', 'telecomunicacoes'])
    expect([...tokens('Água Saneago')].sort()).toEqual(['agua', 'saneago'])
    expect(tokens(null).size).toBe(0)
  })

  it('pagamentos anteriores ensinam como a conta aparece no extrato', () => {
    const keys = keyTokens({ description: 'Internet' }, [' Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA'])
    expect([...keys].sort()).toEqual(['internet', 'linq', 'telecomunicacoes'])
    const occ = { amount: 9999, dueDate: '2035-08-20' }
    const c = { description: 'Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA', amount: 9999, type: 'EXPENSE' as const, date: '2035-08-22' }
    expect(score(occ, bill(5), tokens('Internet'), c)).toBe(0)
    expect(score(occ, bill(5), keys, c)).toBeGreaterThan(0)
  })

  it('boleto pago com multa casa pelo nome; o mercado não casa', () => {
    const occ = { amount: 9999, dueDate: '2035-03-01' }
    const keys = keyTokens({ description: 'Internet LINQ' }, [])
    const linq = { description: 'Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA', amount: 10200, type: 'EXPENSE' as const, date: '2035-03-10' }
    const market = { description: 'SUPERMERCADO ALMEIDA', amount: 10200, type: 'EXPENSE' as const, date: '2035-03-10' }
    // 1 palavra (30) + valor 3% (60 − 6) + 9 dias (50 − 18).
    expect(score(occ, bill(5), keys, linq)).toBe(30 + 54 + 32)
    expect(score(occ, bill(5), keys, market)).toBe(0)
    // Tipo diferente, sem valor ou sem data nunca casa.
    expect(score(occ, bill(5), keys, { ...linq, type: 'INCOME' })).toBe(0)
    expect(score(occ, bill(5), keys, { ...linq, amount: null })).toBe(0)
    expect(score(occ, bill(5), keys, { ...linq, date: null })).toBe(0)
    expect(score(occ, null, keys, linq)).toBe(0)
  })

  it('a precisão controla quanto data e valor podem variar', () => {
    const enel = { amount: 20000, dueDate: '2035-05-05' }
    const enelKeys = keyTokens({ description: 'Energia Enel' }, [])
    // Nível 10: só valor e data exatos.
    expect(score(enel, bill(10), enelKeys, { description: 'PAGTO ENEL DISTRIBUICAO', amount: 20400, type: 'EXPENSE', date: '2035-05-12' })).toBe(0)
    expect(score(enel, bill(10), enelKeys, { description: 'PAGTO ENEL DISTRIBUICAO', amount: 20000, type: 'EXPENSE', date: '2035-05-05' })).toBeGreaterThan(0)
    // Nível 1: praticamente só o nome.
    const agua = { amount: 8000, dueDate: '2035-05-05' }
    const aguaKeys = keyTokens({ description: 'Água Saneago' }, [])
    expect(score(agua, bill(1), aguaKeys, { description: 'SANEAGO SANEAMENTO DE GOIAS', amount: 11500, type: 'EXPENSE', date: '2035-05-28' })).toBeGreaterThan(0)
    // ...mas nem o nível 1 passa de 45 dias.
    expect(score(agua, bill(1), aguaKeys, { description: 'SANEAGO', amount: 8000, type: 'EXPENSE', date: '2035-06-20' })).toBe(0)
  })

  it('tolerâncias por nível e diferença percentual arredondada para cima', () => {
    expect(toleranceFor(0)).toEqual({ amountPercent: -1, days: 45 })
    expect(toleranceFor(5)).toEqual({ amountPercent: 30, days: 15 })
    expect(toleranceFor(99)).toEqual({ amountPercent: 0, days: 0 })
    expect(amountDiffPercent(9999, 10200)).toBe(3)
    expect(amountDiffPercent(20000, 20400)).toBe(2)
    expect(amountDiffPercent(20000, 20000)).toBe(0)
    expect(amountDiffPercent(0, 0)).toBe(0)
    expect(amountDiffPercent(0, 100)).toBe(100)
    expect(amountDiffPercent(null, null)).toBe(0)
  })
})
