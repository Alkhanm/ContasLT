/** Id de registro local (UUID). O usuário continua com id numérico do servidor. */
export type Id = string

export type TransactionType = 'EXPENSE' | 'INCOME'
export type AccountType = 'CHECKING' | 'SAVINGS' | 'CREDIT_CARD' | 'CASH' | 'INVESTMENT' | 'OTHER'
export type Frequency = 'WEEKLY' | 'BIWEEKLY' | 'MONTHLY' | 'BIMONTHLY' | 'QUARTERLY' | 'SEMIANNUAL' | 'YEARLY'
export type ImportSource = 'OFX' | 'EXCEL'

export interface User {
  id: number
  name: string
  email: string
  /** Pode mandar comprovantes para leitura (marcado direto no banco). */
  premium: boolean
}

export interface AuthResponse {
  token: string
  user: User
}

export interface Account {
  id: Id
  name: string
  type: AccountType
  initialBalance: number
  color: string
  archived: boolean
  balance: number
  /** Só cartão de crédito: dia do fechamento e do vencimento da fatura. */
  closingDay: number | null
  dueDay: number | null
}

export interface AccountInput {
  name: string
  type: AccountType
  initialBalance: number
  color: string
  archived?: boolean
  closingDay?: number | null
  dueDay?: number | null
}

export type InvoiceStatus = 'OPEN' | 'CLOSED' | 'PARTIAL' | 'PAID'

/** Fatura de cartão: compras entre dois fechamentos, identificada pelo mês do vencimento. */
export interface CardInvoice {
  accountId: Id
  accountName: string
  month: string
  start: string
  end: string
  dueDate: string
  /** false quando o cartão não tem fechamento/vencimento cadastrados (vale o mês civil). */
  configured: boolean
  /** Compras registradas no período e quantas são. */
  purchases: number
  count: number
  /** Pago: despesas ligadas à fatura. Não detalhado: pago além das compras registradas. */
  paid: number
  undetailed: number
  remaining: number
  status: InvoiceStatus
  paymentTransactionId: Id | null
}

export interface Category {
  id: Id
  name: string
  type: TransactionType
  color: string
  icon: string
  archived: boolean
  /** Categoria pai quando esta é uma subcategoria (um nível só). */
  parentId: Id | null
}

export interface CategoryInput {
  name: string
  type: TransactionType
  color: string
  icon: string
  archived?: boolean
  parentId?: Id | null
}

export interface Transaction {
  id: Id
  description: string
  amount: number
  type: TransactionType
  dueDate: string
  paid: boolean
  notes: string | null
  accountId: Id
  accountName: string
  categoryId: Id | null
  categoryName: string | null
  categoryColor: string | null
  categoryIcon: string | null
  recurringBillId: Id | null
  installmentNumber: number | null
  installmentTotal: number | null
  /** Só em ocorrências de conta recorrente: data prevista pela regra e valor estimado por ela. */
  scheduledDate: string | null
  expectedAmount: number | null
  externalId: string | null
  /** Preenchidos quando o lançamento é uma perna de transferência entre contas. */
  transferId: Id | null
  transferPeerTransactionId: Id | null
  transferPeerAccountId: Id | null
  transferPeerAccountName: string | null
  /** Dados do comprovante (foto de cupom), quando houver. */
  merchant: string | null
  receiptFile: string | null
  receiptAccessKey: string | null
  importBatchId: Id | null
  items: TransactionItem[]
  /** Pagamento de fatura: cartão e mês cujas compras esta despesa agrega; invoice traz o resumo. */
  invoiceCardId: Id | null
  invoiceCardName: string | null
  invoiceMonth: string | null
  invoice: CardInvoice | null
  /** Leitura adiada do comprovante (worker no computador): PENDING, DIVERGENT (total/data lidos diferem) ou FAILED. */
  receiptScanStatus: ReceiptScanStatus | null
  receiptScanTotal: number | null
  receiptScanDate: string | null
}

/** Resultado da importação de dados: quantos entraram e quantos lançamentos já existiam. */
export interface BackupRestored {
  accounts: number
  categories: number
  categoryRules: number
  recurringBills: number
  transactions: number
  skippedTransactions: number
  transfers: number
  receipts: number
}

export type ReceiptScanStatus = 'PENDING' | 'DIVERGENT' | 'FAILED'
/** ACCEPT usa total e data do cupom; DISMISS mantém o lançado; RETRY manda a foto de novo para a fila. */
export type ReceiptScanAction = 'ACCEPT' | 'DISMISS' | 'RETRY'

export interface TransactionItem {
  id: Id | null
  description: string
  quantity: number | null
  unitPrice: number | null
  total: number
}

/** Alcance de uma edição em ocorrência de conta recorrente: só ela, ou ela e os próximos vencimentos. */
export type TransactionEditScope = 'THIS' | 'FOLLOWING'

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
  suggestedCategoryId: Id | null
  suggestedCategoryName: string | null
  duplicateOfTransactionId: Id | null
  duplicateOfDescription: string | null
  duplicateOfDate: string | null
  /** Vencimento pendente de conta recorrente que este comprovante parece pagar. */
  recurringMatchTransactionId: Id | null
  recurringMatchDescription: string | null
  recurringMatchDate: string | null
  recurringMatchAmount: number | null
  recurringMatchCategoryId: Id | null
  recurringMatchAccountId: Id | null
  /**
   * Fila do worker: DONE (dados acima válidos), PENDING (só receiptFile; consultar receiptsApi.job até terminar)
   * ou FAILED (error). workerOnline diz se o computador com o leitor está respondendo agora.
   */
  jobId: number | null
  status: 'DONE' | 'PENDING' | 'FAILED'
  workerOnline: boolean
  error: string | null
}

export interface TransactionInput {
  description: string
  amount: number
  type: TransactionType
  dueDate: string
  paid: boolean
  notes: string | null
  accountId: Id
  categoryId: Id | null
  merchant?: string | null
  receiptFile?: string | null
  receiptAccessKey?: string | null
  items?: TransactionItemInput[]
  /** Pagamento de fatura de cartão: as compras do cartão na fatura do mês ficam agregadas nesta despesa. */
  invoiceCardId?: Id | null
  invoiceMonth?: string | null
}

/**
 * Resumo do período como o extrato da conta (a filtrada, ou todas as ativas de dinheiro); ignora os outros filtros.
 * Saldo anterior = saldo inicial + tudo pago antes do período; só o pago entra em transferência (líquido:
 * entrou − saiu), receitas e despesas; saldo = anterior + transferência + receitas − despesas.
 * Os pendentes ficam à parte e entram só no saldo previsto. `count` é o que está listado, com filtros.
 */
export interface Summary {
  previousBalance: number
  transfer: number
  income: number
  expense: number
  balance: number
  pendingTransfer: number
  pendingIncome: number
  pendingExpense: number
  projectedBalance: number
  count: number
}

export interface DayBalance {
  date: string
  /** Saldo das contas (todas ou a filtrada) ao fim do dia, só com o que está pago. */
  balance: number
}

export interface TransactionPage {
  items: Transaction[]
  summary: Summary
  dayBalances: DayBalance[]
  /** Só quando a lista é de um cartão visto por fatura. */
  invoice: CardInvoice | null
}

export interface TransactionFilter {
  month: string
  accountId?: Id | null
  categoryId?: Id | null
  /** true = só lançamentos sem categoria (ignora categoryId). */
  uncategorized?: boolean | null
  type?: TransactionType | null
  paid?: boolean | null
  search?: string
  /** true = só transferências, false = sem transferências, null/undefined = tudo. */
  transfers?: boolean | null
  /** Conta de cartão: listar pelo período da fatura que vence no mês, em vez do mês civil. */
  invoice?: boolean | null
}

export interface TransferInput {
  fromAccountId: Id
  toAccountId: Id
  amount: number
  date: string
  description: string | null
  paid: boolean
}

export interface Transfer {
  id: Id
  from: Transaction
  to: Transaction
}

export interface TransferSuggestion {
  from: Transaction
  to: Transaction
  daysApart: number
  score: number
}

/** rejected: pares já recusados ("não é transferência"); podem ser esquecidos. */
export interface TransferSuggestionList {
  items: TransferSuggestion[]
  rejected: number
}

export interface RecurringBill {
  id: Id
  description: string
  amount: number
  type: TransactionType
  frequency: Frequency
  startDate: string
  endDate: string | null
  occurrences: number | null
  accountId: Id | null
  accountName: string | null
  categoryId: Id | null
  categoryName: string | null
  categoryColor: string | null
  categoryIcon: string | null
  notes: string | null
  active: boolean
  nextDueDate: string | null
  /** Precisão exigida para casar extrato/comprovante com os vencimentos (1 a 10). */
  matchStrictness: number
}

export interface RecurringBillInput {
  description: string
  amount: number
  type: TransactionType
  frequency: Frequency
  startDate: string
  endDate: string | null
  occurrences: number | null
  accountId: Id
  categoryId: Id | null
  notes: string | null
  active?: boolean
  matchStrictness?: number
  /** Só na criação: lançamento existente que vira a primeira ocorrência da regra. */
  fromTransactionId?: Id | null
}

export interface PreviewRow {
  index: number
  date: string
  description: string
  amount: number
  type: TransactionType
  externalId: string | null
  duplicate: boolean
  suggestedCategoryId: Id | null
  suggestedCategoryName: string | null
  suggestedCategoryColor: string | null
  suggestedCategoryIcon: string | null
  /** Lançamento manual (ex.: de comprovante) que parece ser esta mesma compra. */
  matchTransactionId: Id | null
  matchDescription: string | null
  matchDate: string | null
  matchAccountName: string | null
  matchItemCount: number
  matchHasReceipt: boolean
  matchCategoryId: Id | null
  matchCategoryName: string | null
  /** Casamento com um vencimento pendente de conta recorrente; matchAmount é o valor previsto pela regra. */
  matchRecurring: boolean
  matchAmount: number | null
  /** Linha que parece pagamento de fatura de cartão: cartão sugerido, fatura e o que já consta dela. */
  invoiceCardId: Id | null
  invoiceCardName: string | null
  invoiceMonth: string | null
  invoiceDueDate: string | null
  invoiceTotal: number | null
  invoicePaid: number | null
  /** Extrato do cartão: "pagamento recebido", já representado pela fatura paga na conta. */
  invoicePaymentReceived: boolean
}

export interface ImportPreview {
  source: ImportSource
  filename: string
  accountId: Id
  total: number
  duplicates: number
  rows: PreviewRow[]
}

export interface ConfirmRow {
  date: string
  description: string
  amount: number
  type: TransactionType
  categoryId: Id | null
  externalId: string | null
  paid: boolean
  learnRule: boolean
  /** Vincula a linha a um lançamento existente em vez de criar um novo. */
  linkTransactionId?: Id | null
  /** A linha paga a fatura deste cartão: vira transferência conta → cartão. */
  invoiceCardId?: Id | null
  invoiceMonth?: string | null
}

export interface ImportConfirmRequest {
  accountId: Id
  source: ImportSource
  filename: string
  rows: ConfirmRow[]
}

export interface ImportResult {
  batchId: Id
  imported: number
  skipped: number
  linked: number
  invoicePayments: number
}

export interface ImportBatch {
  id: Id
  source: ImportSource
  filename: string | null
  accountId: Id
  accountName: string
  importedCount: number
  createdAt: string
}

export interface CategoryRule {
  id: Id
  keyword: string
  categoryId: Id
  categoryName: string
  categoryColor: string
  categoryIcon: string
}

export interface CategoryTotal {
  categoryId: Id | null
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

/** Total de um mês, em relatórios. */
export interface MonthValue {
  month: string
  total: number
}

/**
 * Gasto de uma categoria no mês. Em categorias de primeiro nível, `children` traz as subcategorias com gasto
 * ("Geral" é o que foi lançado direto na categoria) e `history` os últimos 6 meses; `previousTotal` é o mês anterior.
 */
export interface CategorySpending {
  categoryId: Id | null
  name: string
  color: string
  icon: string
  total: number
  percent: number
  count: number
  previousTotal: number | null
  children: CategorySpending[]
  history: MonthValue[]
}

export interface CategoryReport {
  month: string
  total: number
  previousTotal: number
  count: number
  items: CategorySpending[]
}

/** Uma compra de um item: a linha do comprovante e o lançamento a que pertence. */
export interface ItemPurchase {
  transactionId: Id
  date: string
  merchant: string | null
  description: string
  quantity: number | null
  unitPrice: number | null
  total: number
}

/** Um item de comprovante agregado pelas compras do período, com categoria da compra mais recente e evolução do preço unitário. */
export interface ItemSpending {
  key: string
  description: string
  categoryId: Id | null
  categoryName: string | null
  color: string
  icon: string
  count: number
  quantity: number
  total: number
  firstUnitPrice: number | null
  lastUnitPrice: number | null
  minUnitPrice: number | null
  maxUnitPrice: number | null
  months: MonthValue[]
  purchases: ItemPurchase[]
}

export interface ItemReport {
  from: string
  to: string
  total: number
  count: number
  items: ItemSpending[]
}
