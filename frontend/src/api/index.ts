import { api } from './client'
import type {
  Account, AccountInput, AuthResponse, Category, CategoryInput, CategoryRule, Dashboard,
  ImportBatch, ImportConfirmRequest, ImportPreview, ImportResult, RecurringBill, RecurringBillInput,
  Transaction, TransactionFilter, TransactionInput, TransactionPage, Transfer, TransferInput, TransferSuggestion, User,
} from './types'

export const authApi = {
  login: (email: string, password: string) =>
    api<AuthResponse>('/api/auth/login', { method: 'POST', body: { email, password } }),
  register: (name: string, email: string, password: string) =>
    api<AuthResponse>('/api/auth/register', { method: 'POST', body: { name, email, password } }),
  me: () => api<User>('/api/auth/me'),
}

export const accountsApi = {
  list: (includeArchived = false) => api<Account[]>('/api/accounts', { query: { includeArchived } }),
  create: (input: AccountInput) => api<Account>('/api/accounts', { method: 'POST', body: input }),
  update: (id: number, input: AccountInput) => api<Account>(`/api/accounts/${id}`, { method: 'PUT', body: input }),
  remove: (id: number) => api<void>(`/api/accounts/${id}`, { method: 'DELETE' }),
}

export const categoriesApi = {
  list: (includeArchived = false) => api<Category[]>('/api/categories', { query: { includeArchived } }),
  create: (input: CategoryInput) => api<Category>('/api/categories', { method: 'POST', body: input }),
  update: (id: number, input: CategoryInput) => api<Category>(`/api/categories/${id}`, { method: 'PUT', body: input }),
  remove: (id: number) => api<void>(`/api/categories/${id}`, { method: 'DELETE' }),
}

export const transactionsApi = {
  list: (filter: TransactionFilter) =>
    api<TransactionPage>('/api/transactions', {
      query: {
        month: filter.month,
        accountId: filter.accountId,
        categoryId: filter.categoryId,
        type: filter.type,
        paid: filter.paid,
        search: filter.search,
        transfers: filter.transfers,
      },
    }),
  create: (input: TransactionInput) => api<Transaction>('/api/transactions', { method: 'POST', body: input }),
  update: (id: number, input: TransactionInput) =>
    api<Transaction>(`/api/transactions/${id}`, { method: 'PUT', body: input }),
  setPaid: (id: number, paid: boolean) =>
    api<Transaction>(`/api/transactions/${id}/paid`, { method: 'PATCH', body: { paid } }),
  remove: (id: number) => api<void>(`/api/transactions/${id}`, { method: 'DELETE' }),
}

export const recurringApi = {
  list: () => api<RecurringBill[]>('/api/recurring-bills'),
  create: (input: RecurringBillInput) => api<RecurringBill>('/api/recurring-bills', { method: 'POST', body: input }),
  update: (id: number, input: RecurringBillInput) =>
    api<RecurringBill>(`/api/recurring-bills/${id}`, { method: 'PUT', body: input }),
  remove: (id: number) => api<void>(`/api/recurring-bills/${id}`, { method: 'DELETE' }),
}

export const importsApi = {
  preview: (accountId: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api<ImportPreview>(`/api/imports/preview?accountId=${accountId}`, { method: 'POST', body: form })
  },
  confirm: (req: ImportConfirmRequest) => api<ImportResult>('/api/imports/confirm', { method: 'POST', body: req }),
  history: () => api<ImportBatch[]>('/api/imports'),
  remove: (id: number) => api<{ removed: number }>(`/api/imports/${id}`, { method: 'DELETE' }),
}

export const transfersApi = {
  create: (input: TransferInput) => api<Transfer>('/api/transfers', { method: 'POST', body: input }),
  link: (transactionId: number, otherTransactionId: number) =>
    api<Transfer>('/api/transfers/link', { method: 'POST', body: { transactionId, otherTransactionId } }),
  suggestions: () => api<{ items: TransferSuggestion[] }>('/api/transfers/suggestions'),
  unlink: (id: number) => api<void>(`/api/transfers/${id}`, { method: 'DELETE' }),
}

export const rulesApi = {
  list: () => api<CategoryRule[]>('/api/category-rules'),
  create: (keyword: string, categoryId: number) =>
    api<CategoryRule>('/api/category-rules', { method: 'POST', body: { keyword, categoryId } }),
  remove: (id: number) => api<void>(`/api/category-rules/${id}`, { method: 'DELETE' }),
}

export const dashboardApi = {
  get: (month: string) => api<Dashboard>('/api/dashboard', { query: { month } }),
}
