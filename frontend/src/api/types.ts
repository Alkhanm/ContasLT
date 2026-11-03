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
  accountId: number
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
  paymentTransactionId: number | null
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
  /** Só em ocorrências de conta recorrente: data prevista pela regra e valor estimado por ela. */
  scheduledDate: string | null
  expectedAmount: number | null
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
  /** Pagamento de fatura: cartão e mês cujas compras esta despesa agrega; invoice traz o resumo. */
  invoiceCardId: number | null
  invoiceCardName: string | null
  invoiceMonth: string | null
  invoice: CardInvoice | null
}

export interface TransactionItem {
  id: number | null
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
  suggestedCategoryId: number | null
  suggestedCategoryName: string | null
  duplicateOfTransactionId: number | null
  duplicateOfDescription: string | null
  duplicateOfDate: string | null
  /** Vencimento pendente de conta recorrente que este comprovante parece pagar. */
  recurringMatchTransactionId: number | null
  recurringMatchDescription: string | null
  recurringMatchDate: string | null
  recurringMatchAmount: number | null
  recurringMatchCategoryId: number | null
  recurringMatchAccountId: number | null
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
  /** Pagamento de fatura de cartão: as compras do cartão na fatura do mês ficam agregadas nesta despesa. */
  invoiceCardId?: number | null
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
  accountId?: number | null
  categoryId?: number | null
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
  accountId: number
  categoryId: number | null
  notes: string | null
  active?: boolean
  matchStrictness?: number
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
  /** Casamento com um vencimento pendente de conta recorrente; matchAmount é o valor previsto pela regra. */
  matchRecurring: boolean
  matchAmount: number | null
  /** Linha que parece pagamento de fatura de cartão: cartão sugerido, fatura e o que já consta dela. */
  invoiceCardId: number | null
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
  /** A linha paga a fatura deste cartão: vira transferência conta → cartão. */
  invoiceCardId?: number | null
  invoiceMonth?: string | null
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
  invoicePayments: number
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
  categoryId: number | null
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
  transactionId: number
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
  categoryId: number | null
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
