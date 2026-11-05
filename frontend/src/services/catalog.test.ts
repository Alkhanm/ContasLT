import { beforeEach, describe, expect, it } from 'vitest'
import type { CategoryInput } from '@/api/types'
import { buildDashboard } from './dashboard'
import { createAccount, createCategory, deleteCategory, listCategories, updateCategory } from './catalog'
import { freshStore } from './testing'
import { createTransaction, listTransactions } from './transactions'

/** Porte de SubcategoryTest. */

const MONTH = '2031-03'

let accountId: string
let casa: string
let energia: string
let agua: string

const payload = (name: string, type: 'EXPENSE' | 'INCOME', parentId: string | null): CategoryInput => ({
  name, type, color: '#3D8361', icon: 'tag', parentId,
})

beforeEach(async () => {
  await freshStore()
  accountId = createAccount({ name: 'Conta principal', type: 'CHECKING', initialBalance: 0, color: '#1B5E3F' }).id
  casa = createCategory(payload('Contas da casa', 'EXPENSE', null)).id
  energia = createCategory(payload('Energia', 'EXPENSE', casa)).id
  agua = createCategory(payload('Água', 'INCOME', casa)).id // tipo informado errado de propósito: segue o pai
})

function transaction(description: string, amount: number, categoryId: string | null) {
  createTransaction({ description, amount, type: 'EXPENSE', dueDate: `${MONTH}-10`, paid: true, notes: null, accountId, categoryId })
}

const find = (id: string, includeArchived = false) => listCategories(includeArchived).find((c) => c.id === id)!
const is422 = expect.objectContaining({ status: 422 })

describe('subcategorias', () => {
  it('subcategoria segue o tipo do pai e só há um nível', () => {
    expect(find(agua).type).toBe('EXPENSE')
    expect(find(agua).parentId).toBe(casa)
    expect(find(casa).parentId).toBeNull()

    expect(() => createCategory(payload('Neta', 'EXPENSE', energia))).toThrow(is422)
    expect(() => updateCategory(casa, payload('Contas da casa', 'EXPENSE', casa))).toThrow(is422)
    expect(() => updateCategory(casa, payload('Contas da casa', 'EXPENSE', energia))).toThrow(is422)
  })

  it('filtrar pelo pai inclui as filhas e o painel agrupa pelo pai', () => {
    transaction('Enel', 200, energia)
    transaction('Saae', 80, agua)
    transaction('Condomínio', 500, casa)
    transaction('Outro', 10, null)

    expect(listTransactions({ month: MONTH, categoryId: casa }).items).toHaveLength(3)

    const byChild = listTransactions({ month: MONTH, categoryId: energia }).items
    expect(byChild).toHaveLength(1)
    expect(byChild[0].description).toBe('Enel')

    const none = listTransactions({ month: MONTH, uncategorized: true }).items
    expect(none).toHaveLength(1)
    expect(none[0].description).toBe('Outro')

    const dash = buildDashboard(MONTH).byCategory
    expect(dash).toHaveLength(2) // Contas da casa (780) e Sem categoria (10)
    expect(dash[0].categoryId).toBe(casa)
    expect(dash[0].total).toBe(780)
    expect(dash[0].count).toBe(3)
    expect(dash[0].percent).toBe(98.7)
    expect(dash[1].categoryId).toBeNull()
  })

  it('pai com filhas não pode ser excluído e arquivar passa para as filhas', () => {
    expect(() => deleteCategory(casa)).toThrow(is422)

    updateCategory(casa, { ...payload('Contas da casa', 'EXPENSE', null), archived: true })
    expect(find(energia, true).archived).toBe(true)
    expect(find(agua, true).archived).toBe(true)

    deleteCategory(energia)
    expect(listCategories(true).some((c) => c.id === energia)).toBe(false)
  })

  // Extras do CategoryService: categoria em uso, desarquivar filha desarquiva o pai, tipo do pai passa às filhas.
  it('regras complementares de categorias', () => {
    transaction('Enel', 200, energia)
    expect(() => deleteCategory(energia)).toThrow(is422)

    updateCategory(casa, { ...payload('Contas da casa', 'EXPENSE', null), archived: true })
    updateCategory(agua, { ...payload('Água', 'EXPENSE', casa), archived: false })
    expect(find(casa).archived).toBe(false)
    expect(find(energia, true).archived).toBe(true)

    const lazer = createCategory(payload('Lazer', 'EXPENSE', null)).id
    const cinema = createCategory(payload('Cinema', 'EXPENSE', lazer)).id
    updateCategory(lazer, payload('Lazer', 'INCOME', null))
    expect(find(cinema).type).toBe('INCOME')

    expect(() => createCategory({ ...payload('', 'EXPENSE', null), color: 'azul' }))
      .toThrow(expect.objectContaining({ status: 400, errors: expect.objectContaining({ name: expect.any(String), color: expect.any(String) }) }))
    expect(createCategory({ ...payload('Sem ícone', 'EXPENSE', null), icon: '  ' }).icon).toBe('tag')
  })
})
