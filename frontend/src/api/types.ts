export type TransactionType = 'EXPENSE' | 'INCOME'
export type AccountType = 'CHECKING' | 'SAVINGS' | 'CREDIT_CARD' | 'CASH' | 'INVESTMENT' | 'OTHER'
export type Frequency = 'WEEKLY' | 'BIWEEKLY' | 'MONTHLY' | 'BIMONTHLY' | 'QUARTERLY' | 'SEMIANNUAL' | 'YEARLY'
export type ImportSource = 'OFX' | 'EXCEL'

export interface User {
  id: number
  name: string
  email: string
}

export interface AuthResponse {
  token: string
  user: User
}

export interface Account {
  id: number
  name: string
  type: AccountType
  initialBalance: number
  color: string
  archived: boolean
  balance: number
}

export interface AccountInput {
  name: string
  type: AccountType
  initialBalance: number
  color: string
  archived?: boolean
}

export interface Category {
  id: number
  name: string
  type: TransactionType
  color: string
  icon: string
  archived: boolean
  /** Categoria pai quando esta é uma subcategoria (um nível só). */
  parentId: number | null
}

export interface CategoryInput {
  name: string
  type: TransactionType
  color: string
  icon: string
  archived?: boolean
  parentId?: number | null
}

export interface Transaction {
  id: number
  description: string
  amount: number
  type: TransactionType
  dueDate: string
  paid: boolean
  notes: string | null
  accountId: number
  accountName: string
  categoryId: number | null
  categoryName: string | null
  categoryColor: string | null
  categoryIcon: string | null
  recurringBillId: number | null
  installmentNumber: number | null
  installmentTotal: number | null
  externalId: string | null
  /** Preenchidos quando o lançamento é uma perna de transferência entre contas. */
  transferId: number | null
  transferPeerTransactionId: number | null
  transferPeerAccountId: number | null
  transferPeerAccountName: string | null
  /** Dados do comprovante (foto de cupom), quando houver. */
  merchant: string | null
  receiptFile: string | null
  receiptAccessKey: string | null
  importBatchId: number | null
  items: TransactionItem[]
}

export interface TransactionItem {
  id: number | null
  description: string
  quantity: number | null
  unitPrice: number | null
  total: number
}

export interface TransactionItemInput {
  description: string
  quantity: number | null
  unitPrice: number | null
  total: number
}

/** Resultado da leitura de um comprovante pelo modelo de visão. */
export interface ReceiptScan {
  receiptFile: string
  merchant: string
  /** Vencimento, se for fatura; senão emissão; senão o dia da leitura. */
  date: string | null
  /** false quando é uma fatura ou boleto ainda a pagar. */
  paid: boolean
  total: number | null
  itemsTotal: number
  paymentMethod: string
  accessKey: string | null
  items: { description: string; quantity: number | null; unitPrice: number | null; total: number }[]
  suggestedCategoryId: number | null
  suggestedCategoryName: string | null
  duplicateOfTransactionId: number | null
  duplicateOfDescription: string | null
  duplicateOfDate: string | null
}

export interface TransactionInput {
  description: string
  amount: number
  type: TransactionType
  dueDate: string
  paid: boolean
  notes: string | null
  accountId: number
  categoryId: number | null
  merchant?: string | null
  receiptFile?: string | null
  receiptAccessKey?: string | null
  items?: TransactionItemInput[]
}

export interface Summary {
  income: number
  expense: number
  balance: number
  pendingIncome: number
  pendingExpense: number
  /** Movimentação interna entre contas, fora de receitas/despesas. */
  transferOut: number
  transferIn: number
  count: number
}

export interface TransactionPage {
  items: Transaction[]
  summary: Summary
}

export interface TransactionFilter {
  month: string
  accountId?: number | null
  categoryId?: number | null
  /** true = só lançamentos sem categoria (ignora categoryId). */
  uncategorized?: boolean | null
  type?: TransactionType | null
  paid?: boolean | null
  search?: string
  /** true = só transferências, false = sem transferências, null/undefined = tudo. */
  transfers?: boolean | null
}

export interface TransferInput {
  fromAccountId: number
  toAccountId: number
  amount: number
  date: string
  description: string | null
  paid: boolean
}

export interface Transfer {
  id: number
  from: Transaction
  to: Transaction
}

export interface TransferSuggestion {
  from: Transaction
  to: Transaction
  daysApart: number
  score: number
}

export interface RecurringBill {
  id: number
  description: string
  amount: number
  type: TransactionType
  frequency: Frequency
  startDate: string
  endDate: string | null
  occurrences: number | null
  accountId: number | null
  accountName: string | null
  categoryId: number | null
  categoryName: string | null
  categoryColor: string | null
  categoryIcon: string | null
  notes: string | null
  active: boolean
  nextDueDate: string | null
}

export interface RecurringBillInput {
  description: string
  amount: number
  type: TransactionType
  frequency: Frequency
  startDate: string
  endDate: string | null
  occurrences: number | null
  accountId: number
  categoryId: number | null
  notes: string | null
  active?: boolean
  /** Só na criação: lançamento existente que vira a primeira ocorrência da regra. */
  fromTransactionId?: number | null
}

export interface PreviewRow {
  index: number
  date: string
  description: string
  amount: number
  type: TransactionType
  externalId: string | null
  duplicate: boolean
  suggestedCategoryId: number | null
  suggestedCategoryName: string | null
  suggestedCategoryColor: string | null
  suggestedCategoryIcon: string | null
  /** Lançamento manual (ex.: de comprovante) que parece ser esta mesma compra. */
  matchTransactionId: number | null
  matchDescription: string | null
  matchDate: string | null
  matchAccountName: string | null
  matchItemCount: number
  matchHasReceipt: boolean
  matchCategoryId: number | null
  matchCategoryName: string | null
}

export interface ImportPreview {
  source: ImportSource
  filename: string
  accountId: number
  total: number
  duplicates: number
  rows: PreviewRow[]
}

export interface ConfirmRow {
  date: string
  description: string
  amount: number
  type: TransactionType
  categoryId: number | null
  externalId: string | null
  paid: boolean
  learnRule: boolean
  /** Vincula a linha a um lançamento existente em vez de criar um novo. */
  linkTransactionId?: number | null
}

export interface ImportConfirmRequest {
  accountId: number
  source: ImportSource
  filename: string
  rows: ConfirmRow[]
}

export interface ImportResult {
  batchId: number
  imported: number
  skipped: number
  linked: number
}

export interface ImportBatch {
  id: number
  source: ImportSource
  filename: string | null
  accountId: number
  accountName: string
  importedCount: number
  createdAt: string
}

export interface CategoryRule {
  id: number
  keyword: string
  categoryId: number
  categoryName: string
  categoryColor: string
  categoryIcon: string
}

export interface CategoryTotal {
  categoryId: number | null
  name: string
  color: string
  icon: string
  total: number
  percent: number
  count: number
}

export interface MonthTotal {
  month: string
  income: number
  expense: number
}

export interface Dashboard {
  month: string
  income: number
  expense: number
  balance: number
  paidIncome: number
  paidExpense: number
  pendingIncome: number
  pendingExpense: number
  /** Total transferido entre contas no mês; não entra em receitas nem despesas. */
  transfers: number
  currentBalance: number
  accounts: Account[]
  byCategory: CategoryTotal[]
  overdue: Transaction[]
  upcoming: Transaction[]
  history: MonthTotal[]
}
