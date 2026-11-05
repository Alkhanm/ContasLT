import { describe, expect, it } from 'vitest'
import type { CategoryRec, CategoryRuleRec, TransactionType } from '@/data/schema'
import { CategorySuggester } from './categorySuggester'

/** Porte de CategorySuggesterTest. */
let seq = 0
function category(name: string, type: TransactionType, archived = false): CategoryRec {
  return { id: `c${++seq}`, name, type, color: '#000000', icon: 'tag', archived, parentId: null, createdAt: '' }
}
const rule = (keyword: string, c: CategoryRec): CategoryRuleRec => ({ id: `r${++seq}`, categoryId: c.id, keyword, createdAt: '' })

const cartao = category('Cartão de crédito', 'EXPENSE')
const impostos = category('Impostos', 'EXPENSE')
const salario = category('Salário', 'INCOME')

describe('CategorySuggester', () => {
  it('sem regra que case, não há sugestão', () => {
    const s = new CategorySuggester([cartao, impostos], [rule('pagamento de fatura', cartao)])
    expect(s.suggest('Pagamento de boleto efetuado - DAS-SIMPLES NACIONAL', 'EXPENSE', null)).toBeNull()
    expect(s.suggest('PAGAMENTO DE FATURA NUBANK', 'EXPENSE', null)).toBe(cartao)
  })

  it('a regra mais longa vence e o tipo precisa bater', () => {
    const s = new CategorySuggester([cartao, impostos, salario],
      [rule('das', impostos), rule('pagamento de fatura', cartao), rule('acme', salario)])
    expect(s.suggest('Pagamento de fatura das compras', 'EXPENSE', null)).toBe(cartao)
    expect(s.suggest('Salario ACME', 'EXPENSE', null)).toBeNull()
    expect(s.suggest('Salario ACME', 'INCOME', null)).toBe(salario)
  })

  it('o nome de categoria vindo do arquivo tem preferência', () => {
    const s = new CategorySuggester([cartao, impostos], [rule('fatura', cartao)])
    expect(s.suggest('Fatura', 'EXPENSE', 'impostos')).toBe(impostos)
    expect(s.suggest('Fatura', 'EXPENSE', 'inexistente')).toBe(cartao)
  })

  it('categoria arquivada nunca é sugerida', () => {
    const old = category('Antiga', 'EXPENSE', true)
    const s = new CategorySuggester([old], [rule('uber', old)])
    expect(s.suggest('Uber viagem', 'EXPENSE', null)).toBeNull()
  })
})
