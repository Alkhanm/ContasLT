import { api } from './client'
import type {
  Account,
  AccountInput,
  AuthResponse,
  BackupRestored,
  Category,
  CategoryInput,
  CategoryReport,
  CategoryRule,
  Dashboard,
  Id,
  ImportBatch,
  ImportConfirmRequest,
  ImportPreview,
  ImportResult,
  ItemReport,
  ReceiptScan,
  ReceiptScanAction,
  RecurringBill,
  RecurringBillInput,
  Transaction,
  TransactionEditScope,
  TransactionFilter,
  TransactionInput,
  TransactionPage,
  Transfer,
  TransferInput,
  TransferSuggestionList,
  User,
} from './types'
import { onAfterSync } from '@/data/session'
import { db } from '@/services/context'
import * as catalog from '@/services/catalog'
import * as transactions from '@/services/transactions'
import * as recurring from '@/services/recurring'
import { buildDashboard } from '@/services/dashboard'
import * as imports from '@/services/imports'
import * as transfers from '@/services/transfers'
import * as reports from '@/services/reports'
import * as backup from '@/services/backup'
import * as receipts from '@/services/receipts'
import { useAuthStore } from '@/stores/auth'

/**
 * Superfície que as telas usam. Quase tudo roda no aparelho (serviços em `@/services`, sobre os dados locais):
 * a função executa a regra, grava na memória e espera o IndexedDB antes de responder; a sincronização com o
 * servidor acontece depois, em segundo plano. Só login e leitura de comprovante falam com o servidor na hora.
 */
// Leituras de comprovante feitas pelo worker enquanto o lançamento já estava salvo chegam depois da sincronização.
onAfterSync(() => receipts.processPendingReceipts())

async function local<T>(fn: () => T | Promise<T>): Promise<T> {
  const result = await fn()
  await db().flush()
  return result
}

export const authApi = {
  login: (email: string, password: string) =>
    api<AuthResponse>('/api/auth/login', { method: 'POST', body: { email, password } }),
  register: (name: string, email: string, password: string) =>
    api<AuthResponse>('/api/auth/register', { method: 'POST', body: { name, email, password } }),
  google: (idToken: string) => api<AuthResponse>('/api/auth/google', { method: 'POST', body: { idToken } }),
  forgotPassword: (email: string) => api<void>('/api/auth/forgot-password', { method: 'POST', body: { email } }),
  resetPassword: (email: string, code: string, password: string) =>
    api<AuthResponse>('/api/auth/reset-password', { method: 'POST', body: { email, code, password } }),
  me: () => api<User>('/api/auth/me'),
}

export const accountsApi = {
  list: (includeArchived = false) => local<Account[]>(() => catalog.listAccounts(includeArchived)),
  create: (input: AccountInput) => local<Account>(() => catalog.createAccount(input)),
  update: (id: Id, input: AccountInput) => local<Account>(() => catalog.updateAccount(id, input)),
  remove: (id: Id) => local<void>(() => catalog.deleteAccount(id)),
}

export const categoriesApi = {
  list: (includeArchived = false) => local<Category[]>(() => catalog.listCategories(includeArchived)),
  create: (input: CategoryInput) => local<Category>(() => catalog.createCategory(input)),
  update: (id: Id, input: CategoryInput) => local<Category>(() => catalog.updateCategory(id, input)),
  remove: (id: Id) => local<void>(() => catalog.deleteCategory(id)),
}

export const transactionsApi = {
  list: (filter: TransactionFilter) => local<TransactionPage>(() => transactions.listTransactions(filter)),
  create: (input: TransactionInput) => local<Transaction>(async () => {
    const res = transactions.createTransaction(input)
    if (res.receiptAttached) await receipts.attachReceipt(res.receiptAttached.id)
    return res.receiptAttached ? transactions.getTransaction(res.transaction.id) : res.transaction
  }),
  update: (id: Id, input: TransactionInput, scope?: TransactionEditScope) => local<Transaction>(async () => {
    const res = transactions.updateTransaction(id, input, scope)
    if (res.receiptAttached) await receipts.attachReceipt(res.receiptAttached.id)
    return res.receiptAttached ? transactions.getTransaction(res.transaction.id) : res.transaction
  }),
  setPaid: (id: Id, paid: boolean) => local<Transaction>(() => transactions.setPaid(id, paid)),
  remove: (id: Id) => local<void>(() => transactions.deleteTransaction(id)),
  /** Responde ao aviso de leitura adiada do comprovante. "Ler de novo" manda a foto para a fila do servidor. */
  receiptScan: (id: Id, action: ReceiptScanAction) => local<Transaction>(async () =>
    action === 'RETRY' ? receipts.retryReceipt(id) : transactions.resolveReceiptScan(id, action)),
}

export const recurringApi = {
  list: () => local<RecurringBill[]>(() => recurring.listRecurring()),
  create: (input: RecurringBillInput) => local<RecurringBill>(() => recurring.createRecurring(input)),
  update: (id: Id, input: RecurringBillInput) => local<RecurringBill>(() => recurring.updateRecurring(id, input)),
  remove: (id: Id) => local<void>(() => recurring.deleteRecurring(id)),
}

export const importsApi = {
  preview: (accountId: Id, file: File) => local<ImportPreview>(async () =>
    imports.previewImport(accountId, { name: file.name, bytes: new Uint8Array(await file.arrayBuffer()) })),
  confirm: (req: ImportConfirmRequest) => local<ImportResult>(() => imports.confirmImport(req)),
  history: () => local<ImportBatch[]>(() => imports.importHistory()),
  remove: (id: Id) => local<{ removed: number }>(() => imports.deleteImport(id)),
}

export const transfersApi = {
  create: (input: TransferInput) => local<Transfer>(() => transfers.createTransfer(input)),
  link: (transactionId: Id, otherTransactionId: Id) => local<Transfer>(() => transfers.linkTransfer(transactionId, otherTransactionId)),
  suggestions: () => local<TransferSuggestionList>(() => transfers.transferSuggestions(useAuthStore().user?.name ?? null)),
  /** "Não é transferência". Entre duas contas recorrentes a recusa vale para todos os meses (recurring: true). */
  reject: (transactionId: Id, otherTransactionId: Id) =>
    local<{ recurring: boolean }>(() => transfers.rejectTransfer(transactionId, otherTransactionId)),
  forgetRejections: () => local<void>(() => transfers.forgetRejections()),
  unlink: (id: Id) => local<void>(() => transfers.unlinkTransfer(id)),
}

export const receiptsApi = {
  /** Envia a foto para o servidor ler; as sugestões (categoria, duplicata, recorrente) são calculadas aqui. */
  scan: (file: File, ignoreTransactionId?: Id | null) => local<ReceiptScan>(() => receipts.scanReceipt(file, ignoreTransactionId ?? null)),
  /** Estado de uma leitura na fila do worker; pronta, traz o mesmo que o scan direto. */
  job: (jobId: number, ignoreTransactionId?: Id | null) => local<ReceiptScan>(() => receipts.receiptJob(jobId, ignoreTransactionId ?? null)),
  imageUrl: (file: string) => receipts.receiptImageUrl(file),
}

/** Exportar/importar os dados da conta (zip com JSON + fotos), gerado e lido no aparelho. Só para contas premium. */
export const backupApi = {
  export: async (): Promise<Blob> => {
    const bytes = await backup.exportBackup({ downloadReceipt: receipts.downloadReceipt })
    return new Blob([bytes as BlobPart], { type: 'application/zip' })
  },
  /** Soma os dados do arquivo aos da conta; o que já existe é pulado. */
  import: (file: File) => local<BackupRestored>(async () =>
    backup.importBackup(new Uint8Array(await file.arrayBuffer()), { uploadReceipt: receipts.uploadReceipt })),
}

export const rulesApi = {
  list: () => local<CategoryRule[]>(() => catalog.listRules()),
  create: (keyword: string, categoryId: Id) => local<CategoryRule>(() => catalog.createRule(keyword, categoryId)),
  remove: (id: Id) => local<void>(() => catalog.deleteRule(id)),
}

export const dashboardApi = {
  get: (month: string) => local<Dashboard>(() => buildDashboard(month)),
}

export const reportsApi = {
  /** Despesas do mês por categoria, com subcategorias, mês anterior e 6 meses de histórico. */
  categories: (month: string) => local<CategoryReport>(() => reports.categoryReport(month)),
  /** Itens de comprovante entre dois meses (inclusive), agrupados por nome; sem `from`/`to`, os últimos 12 meses. */
  items: (filter: { from?: string; to?: string; categoryId?: Id | null; search?: string }) =>
    local<ItemReport>(() => reports.itemReport(filter)),
}
