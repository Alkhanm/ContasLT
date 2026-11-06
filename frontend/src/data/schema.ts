/**
 * Formato dos registros guardados no aparelho e sincronizados com o servidor (`sync_records.data`). É o contrato
 * entre os clientes (este PWA e o app Android): o servidor não lê os campos. Mudanças aqui precisam continuar
 * lendo registros antigos (campo novo = opcional), porque aparelhos desatualizados seguem gravando o formato velho.
 *
 * Convenções: ids são UUIDs; valores monetários em **centavos** (inteiro); datas `AAAA-MM-DD`; meses de fatura no
 * primeiro dia (`AAAA-MM-01`); `createdAt` ISO local. A migração do servidor (V13) gera exatamente este formato.
 */

export type Id = string
export type Cents = number
export type IsoDate = string

export type TransactionType = 'EXPENSE' | 'INCOME'
export type AccountType = 'CHECKING' | 'SAVINGS' | 'CREDIT_CARD' | 'CASH' | 'INVESTMENT' | 'OTHER'
export type Frequency = 'WEEKLY' | 'BIWEEKLY' | 'MONTHLY' | 'BIMONTHLY' | 'QUARTERLY' | 'SEMIANNUAL' | 'YEARLY'
export type ImportSource = 'OFX' | 'EXCEL'
export type ReceiptScanStatus = 'PENDING' | 'DIVERGENT' | 'FAILED'

export interface AccountRec {
  id: Id
  name: string
  type: AccountType
  initialBalance: Cents
  color: string
  archived: boolean
  /** Só cartão de crédito. */
  closingDay: number | null
  dueDay: number | null
  createdAt: string
}

export interface CategoryRec {
  id: Id
  name: string
  type: TransactionType
  color: string
  icon: string
  archived: boolean
  parentId: Id | null
  createdAt: string
}

export interface CategoryRuleRec {
  id: Id
  categoryId: Id
  /** Normalizada (minúsculas, sem acento). */
  keyword: string
  createdAt: string
}

export interface RecurringBillRec {
  id: Id
  accountId: Id | null
  categoryId: Id | null
  description: string
  amount: Cents
  type: TransactionType
  frequency: Frequency
  startDate: IsoDate
  endDate: IsoDate | null
  occurrences: number | null
  /** Número da ocorrência em startDate (1 = primeira); muda quando a regra é reancorada. */
  startNumber: number
  /** 1 a 10: precisão para casar extrato/comprovante com os vencimentos. */
  matchStrictness: number
  notes: string | null
  active: boolean
  createdAt: string
}

export interface ImportBatchRec {
  id: Id
  accountId: Id
  source: ImportSource
  filename: string | null
  importedCount: number
  createdAt: string
}

export interface ItemRec {
  description: string
  quantity: number | null
  unitPrice: Cents | null
  total: Cents
}

export interface TransactionRec {
  id: Id
  accountId: Id
  categoryId: Id | null
  recurringBillId: Id | null
  importBatchId: Id | null
  transferId: Id | null
  description: string
  amount: Cents
  type: TransactionType
  dueDate: IsoDate
  /** Ocorrência de recorrente: data prevista pela regra. */
  scheduledDate: IsoDate | null
  paid: boolean
  notes: string | null
  /** FITID do OFX ou chave da linha do extrato. */
  externalId: string | null
  installmentNumber: number | null
  merchant: string | null
  receiptFile: string | null
  receiptAccessKey: string | null
  /** Lançamento manual vinculado a uma linha de extrato (excluir o lote só desvincula). */
  linkedImport: boolean
  receiptScanStatus: ReceiptScanStatus | null
  receiptScanTotal: Cents | null
  receiptScanDate: IsoDate | null
  /** Pagamento de fatura: cartão e mês (AAAA-MM-01) cujas compras esta despesa agrega. */
  cardAccountId: Id | null
  cardInvoiceMonth: IsoDate | null
  items: ItemRec[]
  createdAt: string
}

export interface TransferRec {
  id: Id
  fromTransactionId: Id
  toTransactionId: Id
  createdAt: string
}

/** "Não é transferência": chaves `bill:<id>` (todas as ocorrências da regra) ou `tx:<id>`, com aKey < bKey. */
export interface TransferRejectionRec {
  id: Id
  aKey: string
  bKey: string
  createdAt: string
}

export interface EntityMap {
  account: AccountRec
  category: CategoryRec
  categoryRule: CategoryRuleRec
  recurringBill: RecurringBillRec
  importBatch: ImportBatchRec
  transaction: TransactionRec
  transfer: TransferRec
  transferRejection: TransferRejectionRec
}

export type Entity = keyof EntityMap

export const ENTITIES: Entity[] = [
  'account', 'category', 'categoryRule', 'recurringBill', 'importBatch', 'transaction', 'transfer', 'transferRejection',
]

/** Registro com o "relógio" da última gravação (epoch ms), usado para decidir conflitos. */
export interface Stored<E extends Entity = Entity> {
  id: Id
  entity: E
  data: EntityMap[E] | null
  deleted: boolean
  updatedAt: number
}

/**
 * updatedAt dos vencimentos de recorrente gerados automaticamente. Qualquer edição de verdade (relógio atual) vence
 * um vencimento gerado em outro aparelho com o mesmo id, então gerar de novo nunca desfaz um pagamento.
 */
export const GENERATED_AT = 1
