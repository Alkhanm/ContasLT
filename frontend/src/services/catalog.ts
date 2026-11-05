import type { Account, AccountInput, Category, CategoryInput, CategoryRule } from '@/api/types'
import type { AccountRec, CategoryRec, CategoryRuleRec, Id, TransactionType } from '@/data/schema'
import { nowIso } from '@/domain/dates'
import { newId } from '@/domain/ids'
import { toCents } from '@/domain/money'
import { normalize, truncate } from '@/domain/text'
import { BusinessError, db, require, validate } from './context'
import { accountBalance, accountDto, categoryDto, paidMovementsByAccount, ruleDto } from './mappers'

/** Contas, categorias e regras de categorização (porte de AccountService, CategoryService e CategoryRuleController). */

const COLOR = /^#[0-9a-fA-F]{6}$/
const collator = new Intl.Collator('pt-BR')

// ---------- Contas ----------

export function listAccounts(includeArchived = false): Account[] {
  const s = db()
  const txs = s.list('transaction')
  const movements = paidMovementsByAccount(txs)
  return s.list('account')
    .filter((a) => includeArchived || !a.archived)
    .sort((a, b) => Number(a.archived) - Number(b.archived) || collator.compare(a.name, b.name))
    .map((a) => accountDto(a, accountBalance(a, movements, txs)))
}

export function accountById(id: Id): Account {
  const a = require(db().get('account', id), 'Conta não encontrada')
  const txs = db().list('transaction')
  return accountDto(a, accountBalance(a, paidMovementsByAccount(txs), txs))
}

function applyAccount(a: AccountRec, req: AccountInput) {
  validate([
    ['name', !!req.name?.trim(), 'Informe o nome da conta'],
    ['name', (req.name ?? '').trim().length <= 80, 'Nome muito longo'],
    ['type', !!req.type, 'Informe o tipo'],
    ['color', req.color == null || COLOR.test(req.color), 'Cor inválida'],
    ['closingDay', req.closingDay == null || (req.closingDay >= 1 && req.closingDay <= 28), 'Use um dia entre 1 e 28'],
    ['dueDay', req.dueDay == null || (req.dueDay >= 1 && req.dueDay <= 28), 'Use um dia entre 1 e 28'],
  ])
  a.name = req.name.trim()
  a.type = req.type
  a.initialBalance = toCents(req.initialBalance ?? 0)
  if (req.color) a.color = req.color
  if (req.archived != null) a.archived = req.archived
  if (req.type === 'CREDIT_CARD') {
    if ((req.closingDay == null) !== (req.dueDay == null)) {
      throw new BusinessError('Informe o dia de fechamento e o de vencimento da fatura')
    }
    if (req.closingDay != null && req.closingDay === req.dueDay) {
      throw new BusinessError('Fechamento e vencimento precisam ser dias diferentes')
    }
    a.closingDay = req.closingDay ?? null
    a.dueDay = req.dueDay ?? null
  } else {
    a.closingDay = null
    a.dueDay = null
  }
}

export function createAccount(req: AccountInput): Account {
  const a: AccountRec = {
    id: newId(), name: '', type: 'CHECKING', initialBalance: 0, color: '#1B5E3F', archived: false,
    closingDay: null, dueDay: null, createdAt: nowIso(),
  }
  applyAccount(a, req)
  db().put('account', a)
  return accountById(a.id)
}

export function updateAccount(id: Id, req: AccountInput): Account {
  const a = structuredClone(require(db().get('account', id), 'Conta não encontrada'))
  applyAccount(a, req)
  db().put('account', a)
  return accountById(id)
}

export function deleteAccount(id: Id) {
  require(db().get('account', id), 'Conta não encontrada')
  if (db().list('transaction').some((t) => t.accountId === id)) {
    throw new BusinessError('Esta conta possui lançamentos. Arquive-a em vez de excluir.')
  }
  db().remove('account', id)
}

// ---------- Categorias ----------

const DEFAULT_CATEGORIES: [string, TransactionType, string, string][] = [
  ['Moradia', 'EXPENSE', '#2F6B4F', 'house'],
  ['Contas da casa', 'EXPENSE', '#3D8361', 'plug-zap'],
  ['Mercado', 'EXPENSE', '#C98A2B', 'shopping-cart'],
  ['Alimentação', 'EXPENSE', '#D9622B', 'utensils'],
  ['Transporte', 'EXPENSE', '#3A6EA5', 'car'],
  ['Saúde', 'EXPENSE', '#C2452D', 'heart-pulse'],
  ['Educação', 'EXPENSE', '#6B4FBB', 'graduation-cap'],
  ['Assinaturas', 'EXPENSE', '#8A5CF6', 'repeat'],
  ['Lazer', 'EXPENSE', '#E0A526', 'popcorn'],
  ['Vestuário', 'EXPENSE', '#B5537A', 'shirt'],
  ['Impostos e taxas', 'EXPENSE', '#6B7280', 'landmark'],
  ['Cartão de crédito', 'EXPENSE', '#4B5563', 'credit-card'],
  ['Outras despesas', 'EXPENSE', '#9CA3AF', 'circle-ellipsis'],
  ['Salário', 'INCOME', '#1B5E3F', 'briefcase'],
  ['Freelance', 'INCOME', '#2E8B57', 'laptop'],
  ['Investimentos', 'INCOME', '#0F766E', 'trending-up'],
  ['Outras receitas', 'INCOME', '#6B7280', 'circle-plus'],
]

/** Conta nova: categorias padrão e uma "Conta principal" (antes feito no cadastro, no servidor). */
export function seedDefaults() {
  const s = db()
  const createdAt = nowIso()
  for (const [name, type, color, icon] of DEFAULT_CATEGORIES) {
    s.put('category', { id: newId(), name, type, color, icon, archived: false, parentId: null, createdAt })
  }
  s.put('account', {
    id: newId(), name: 'Conta principal', type: 'CHECKING', initialBalance: 0, color: '#1B5E3F', archived: false,
    closingDay: null, dueDay: null, createdAt,
  })
}

const sortCategories = (a: CategoryRec, b: CategoryRec) =>
  (a.type < b.type ? -1 : a.type > b.type ? 1 : 0) || collator.compare(a.name, b.name)

export function listCategories(includeArchived = false): Category[] {
  return db().list('category').filter((c) => includeArchived || !c.archived).sort(sortCategories).map(categoryDto)
}

export function requireCategory(id: Id): CategoryRec {
  return require(db().get('category', id), 'Categoria não encontrada')
}

export function requireCategoryOrNull(id: Id | null | undefined): CategoryRec | null {
  return id == null ? null : requireCategory(id)
}

export function childrenOf(id: Id) {
  return db().list('category').filter((c) => c.parentId === id)
}

/** A categoria e as subcategorias dela: filtrar pelo pai inclui as filhas. */
export function idsWithChildren(id: Id): Id[] {
  const c = requireCategory(id)
  return [c.id, ...childrenOf(c.id).map((x) => x.id)]
}

/** Categoria de primeiro nível (a própria, ou o pai de uma subcategoria). */
export function rootOf(c: CategoryRec): CategoryRec {
  return (c.parentId && db().get('category', c.parentId)) || c
}

function resolveParent(current: CategoryRec | null, parentId: Id | null | undefined): CategoryRec | null {
  if (parentId == null) return null
  if (current && parentId === current.id) throw new BusinessError('Uma categoria não pode ser subcategoria dela mesma')
  const parent = requireCategory(parentId)
  if (parent.parentId != null) {
    throw new BusinessError(`"${parent.name}" já é uma subcategoria. Só há um nível de subcategorias.`)
  }
  if (current && childrenOf(current.id).length) {
    throw new BusinessError(`"${current.name}" tem subcategorias e não pode virar subcategoria.`)
  }
  return parent
}

function applyCategory(c: CategoryRec, req: CategoryInput, parent: CategoryRec | null) {
  validate([
    ['name', !!req.name?.trim(), 'Informe o nome da categoria'],
    ['name', (req.name ?? '').trim().length <= 60, 'Nome muito longo'],
    ['type', !!req.type, 'Informe o tipo'],
    ['color', !!req.color, 'Escolha uma cor'],
    ['color', !req.color || COLOR.test(req.color), 'Cor inválida'],
  ])
  c.name = req.name.trim()
  c.parentId = parent?.id ?? null
  // Subcategoria sempre tem o tipo do pai.
  c.type = parent ? parent.type : req.type
  c.color = req.color
  c.icon = req.icon?.trim() ? req.icon : 'tag'
  if (req.archived != null) c.archived = req.archived
}

export function createCategory(req: CategoryInput): Category {
  const c: CategoryRec = { id: newId(), name: '', type: 'EXPENSE', color: '', icon: 'tag', archived: false, parentId: null, createdAt: nowIso() }
  applyCategory(c, req, resolveParent(null, req.parentId))
  db().put('category', c)
  return categoryDto(c)
}

export function updateCategory(id: Id, req: CategoryInput): Category {
  const s = db()
  const c = structuredClone(requireCategory(id))
  applyCategory(c, req, resolveParent(c, req.parentId))
  s.put('category', c)
  const parent = c.parentId ? s.get('category', c.parentId) : null
  if (!c.archived && parent?.archived) s.put('category', { ...parent, archived: false })
  for (const child of childrenOf(id)) {
    const next = { ...child, type: c.type, archived: c.archived ? true : child.archived }
    if (next.type !== child.type || next.archived !== child.archived) s.put('category', next)
  }
  return categoryDto(c)
}

export function deleteCategory(id: Id) {
  requireCategory(id)
  if (childrenOf(id).length) {
    throw new BusinessError('Esta categoria tem subcategorias. Exclua ou mova as subcategorias antes.')
  }
  if (db().list('transaction').some((t) => t.categoryId === id)) {
    throw new BusinessError('Esta categoria está em uso. Arquive-a em vez de excluir.')
  }
  db().remove('category', id)
  for (const r of db().list('categoryRule')) if (r.categoryId === id) db().remove('categoryRule', r.id)
}

// ---------- Regras de categorização ----------

export function listRules(): CategoryRule[] {
  return db().list('categoryRule')
    .sort((a, b) => (a.keyword < b.keyword ? -1 : a.keyword > b.keyword ? 1 : 0))
    .map(ruleDto)
    .filter((r): r is CategoryRule => r !== null)
}

/** Cria a regra, ou troca a categoria de uma regra com a mesma palavra-chave. */
export function saveRule(keyword: string, categoryId: Id): CategoryRuleRec {
  const category = requireCategory(categoryId)
  const key = truncate(normalize(keyword), 120)
  validate([['keyword', key.length > 0, 'Informe a palavra-chave']])
  const existing = db().list('categoryRule').find((r) => r.keyword === key)
  const rule: CategoryRuleRec = existing
    ? { ...existing, categoryId: category.id }
    : { id: newId(), categoryId: category.id, keyword: key, createdAt: nowIso() }
  if (!existing || existing.categoryId !== category.id) db().put('categoryRule', rule)
  return rule
}

export function createRule(keyword: string, categoryId: Id): CategoryRule {
  return ruleDto(saveRule(keyword, categoryId))!
}

export function deleteRule(id: Id) {
  require(db().get('categoryRule', id), 'Regra não encontrada')
  db().remove('categoryRule', id)
}
