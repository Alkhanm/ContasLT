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
}

export interface CategoryInput {
  name: string
  type: TransactionType
  color: string
  icon: string
  archived?: boolean
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
}

export interface Summary {
  income: number
  expense: number
  balance: number
  pendingIncome: number
  pendingExpense: number
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
  type?: TransactionType | null
  paid?: boolean | null
  search?: string
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
  currentBalance: number
  accounts: Account[]
  byCategory: CategoryTotal[]
  overdue: Transaction[]
  upcoming: Transaction[]
  history: MonthTotal[]
}
