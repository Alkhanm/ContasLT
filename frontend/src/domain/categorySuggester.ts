import type { CategoryRec, CategoryRuleRec, TransactionType } from '@/data/schema'
import { normalize } from './text'

/**
 * Sugere categoria para uma descrição (porte de CategorySuggester): 1) nome de categoria vindo do arquivo,
 * 2) regras do usuário. Não adivinha pelo histórico de lançamentos: é melhor ficar sem categoria do que mal
 * categorizado.
 *
 * `categories` na ordem em que o nome do arquivo deve ser procurado (tipo, nome); `rules` em qualquer ordem: a
 * palavra-chave mais longa vence (empate: ordem alfabética da palavra-chave).
 */
export class CategorySuggester {
  private readonly categories: CategoryRec[]
  private readonly rules: { keyword: string; category: CategoryRec }[]

  constructor(categories: CategoryRec[], rules: CategoryRuleRec[]) {
    this.categories = categories
    const byId = new Map(categories.map((c) => [c.id, c]))
    this.rules = [...rules]
      .sort((a, b) => (a.keyword < b.keyword ? -1 : a.keyword > b.keyword ? 1 : 0))
      .sort((a, b) => b.keyword.length - a.keyword.length)
      .flatMap((r) => {
        const category = byId.get(r.categoryId)
        return category ? [{ keyword: normalize(r.keyword), category }] : []
      })
  }

  suggest(description: string, type: TransactionType, categoryHint: string | null | undefined): CategoryRec | null {
    if (categoryHint != null && categoryHint.trim() !== '') {
      const hint = normalize(categoryHint)
      const byName = this.categories.find((c) => c.type === type && normalize(c.name) === hint)
      if (byName) return byName
    }
    const norm = normalize(description)
    if (!norm) return null
    for (const rule of this.rules) {
      if (rule.keyword && norm.includes(rule.keyword) && rule.category.type === type && !rule.category.archived) {
        return rule.category
      }
    }
    return null
  }
}
