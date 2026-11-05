import { read, SSF, utils, type CellObject, type WorkSheet } from 'xlsx'
import type { TransactionType } from '@/data/schema'
import type { IsoDate } from './dates'
import type { Cents } from './money'
import { decimalToCents, parseAmount, ParseError, smartDate, toBytes, type Bytes, type ParsedRow } from './ofx'
import { normalize, truncate } from './text'

/**
 * Leitor de planilhas (.xlsx/.xls), porte de ExcelParser. Procura uma linha de cabeçalho com colunas de data,
 * descrição e valor; sem cabeçalho, assume a ordem data | descrição | valor. Colunas opcionais: tipo e categoria.
 */

interface Columns {
  date: number
  description: number
  amount: number
  type: number
  category: number
}

const JAVA_SPACES = /[ \t\n\x0B\f\r]+/g

type Cell = CellObject | undefined

export function parseExcel(input: Bytes): ParsedRow[] {
  const bytes = toBytes(input)
  let sheet: WorkSheet | undefined
  let date1904 = false
  try {
    // Como o WorkbookFactory do POI: só aceita OOXML (zip) ou OLE2 (.xls binário).
    if (!isZip(bytes) && !isOle2(bytes)) throw new Error('o arquivo não é uma planilha do Excel (.xlsx ou .xls)')
    const wb = read(bytes, { type: 'array', cellNF: true, cellDates: false })
    date1904 = !!wb.Workbook?.WBProps?.date1904
    sheet = wb.Sheets[wb.SheetNames[0]]
    if (!sheet) throw new Error('a planilha não tem abas')
  } catch (e) {
    throw new ParseError(`Não foi possível ler a planilha: ${e instanceof Error ? e.message : String(e)}`)
  }
  const ref = sheet['!ref']
  const range = ref ? utils.decode_range(ref) : null
  const rows: ParsedRow[] = []
  if (range) {
    const cell = (r: number, c: number): Cell => (c < 0 ? undefined : sheet[utils.encode_cell({ r, c })] as Cell)
    const first = range.s.r
    const lastRow = range.e.r
    let headerRow = -1
    let cols: Columns | null = null
    const last = Math.min(lastRow, first + 30)
    for (let r = first; r <= last; r++) {
      const c = detectHeader(r, range.s.c, range.e.c, cell)
      if (c) {
        headerRow = r
        cols = c
        break
      }
    }
    cols ??= { date: 0, description: 1, amount: 2, type: -1, category: -1 }
    for (let r = Math.max(headerRow + 1, first); r <= lastRow; r++) {
      const row = parseRow((c) => cell(r, c), cols, date1904)
      if (row) rows.push(row)
    }
  }
  if (!rows.length) {
    throw new ParseError('Nenhuma linha com data e valor foi encontrada na planilha. Use colunas Data, Descrição e Valor.')
  }
  return rows
}

const isZip = (b: Uint8Array) => b.length > 4 && b[0] === 0x50 && b[1] === 0x4b
const isOle2 = (b: Uint8Array) => b.length > 8 && b[0] === 0xd0 && b[1] === 0xcf && b[2] === 0x11 && b[3] === 0xe0

function detectHeader(r: number, c0: number, c1: number, cell: (r: number, c: number) => Cell): Columns | null {
  let date = -1
  let desc = -1
  let amount = -1
  let type = -1
  let category = -1
  for (let i = c0; i <= c1; i++) {
    const c = cell(r, i)
    if (!c) continue
    if (c.t !== 's') continue
    const h = normalize(text(c))
    if (date < 0 && (h.startsWith('data') || h === 'date' || h.startsWith('dt ') || h.includes('vencimento'))) {
      date = i
    } else if (desc < 0 && (h.includes('descri') || h.includes('historico') || h.includes('memo')
      || h.includes('lancamento') || h.includes('estabelecimento') || h.includes('titulo') || h.includes('description'))) {
      desc = i
    } else if (amount < 0 && (h.includes('valor') || h.includes('amount') || h.includes('value') || h.includes('montante'))) {
      amount = i
    } else if (type < 0 && (h === 'tipo' || h === 'type' || h.includes('natureza') || h.includes('credito debito'))) {
      type = i
    } else if (category < 0 && (h.includes('categoria') || h.includes('category'))) {
      category = i
    }
  }
  if (date >= 0 && amount >= 0) {
    if (desc < 0) desc = findFreeColumn(date, amount)
    return { date, description: desc, amount, type, category }
  }
  return null
}

function findFreeColumn(a: number, b: number) {
  for (let i = 0; i < 10; i++) if (i !== a && i !== b) return i
  return 1
}

function parseRow(cell: (c: number) => Cell, cols: Columns, date1904: boolean): ParsedRow | null {
  const date = readDate(cell(cols.date), date1904)
  const amount = readAmount(cell(cols.amount))
  if (date == null || amount == null || amount === 0) return null
  let description = text(cell(cols.description))
  if (description.trim() === '') description = 'Lançamento'
  let type: TransactionType = amount < 0 ? 'EXPENSE' : 'INCOME'
  if (cols.type >= 0) {
    const t = normalize(text(cell(cols.type)))
    if (t.startsWith('d') || t.includes('saida') || t.includes('despesa') || t.includes('pagamento')) {
      type = 'EXPENSE'
    } else if (t.startsWith('c') || t.includes('entrada') || t.includes('receita') || t.includes('recebimento')) {
      type = 'INCOME'
    }
  }
  const categoryHint = cols.category >= 0 ? text(cell(cols.category)) : null
  return {
    date,
    description: truncate(description.replace(JAVA_SPACES, ' '), 140),
    amount: Math.abs(amount),
    type,
    externalId: null,
    categoryHint: categoryHint == null || categoryHint.trim() === '' ? null : categoryHint.trim(),
  }
}

/** Texto formatado da célula, como o DataFormatter do POI. */
function text(c: Cell): string {
  if (!c || c.t === 'z') return ''
  const s = c.w ?? (c.v == null ? '' : String(c.v))
  return s.trim()
}

/** Formatos aceitos quando a data vem como texto (na ordem do ExcelParser). */
const DATE_FORMATS: ((s: string) => IsoDate | null)[] = [
  fmt(/^(\d{2})\/(\d{2})\/(\d{4})$/, 3, 2, 1),          // dd/MM/yyyy
  fmt(/^(\d+)\/(\d+)\/(\d{4})$/, 3, 2, 1),              // d/M/yyyy
  fmt(/^(\d{2})\/(\d{2})\/(\d{2})$/, 3, 2, 1, 2000),    // dd/MM/yy
  fmt(/^(\d{4})-(\d{2})-(\d{2})$/, 1, 2, 3),            // yyyy-MM-dd
  fmt(/^(\d{2})-(\d{2})-(\d{4})$/, 3, 2, 1),            // dd-MM-yyyy
  fmt(/^(\d{2})\.(\d{2})\.(\d{4})$/, 3, 2, 1),          // dd.MM.yyyy
]

function fmt(re: RegExp, yi: number, mi: number, di: number, base = 0) {
  return (s: string): IsoDate | null => {
    const m = re.exec(s)
    return m ? smartDate(base + Number(m[yi]), Number(m[mi]), Number(m[di])) : null
  }
}

export function readDate(c: Cell, date1904 = false): IsoDate | null {
  if (!c) return null
  if (c.t === 'n' && typeof c.v === 'number' && c.z != null && SSF.is_date(c.z)) {
    const d = SSF.parse_date_code(c.v, { date1904 }) as { y: number; m: number; d: number } | null
    if (d) return smartDate(d.y, d.m, d.d)
  }
  let s = text(c)
  if (s.length > 10) s = s.slice(0, 10)
  for (const f of DATE_FORMATS) {
    const d = f(s)
    if (d) return d
  }
  return null
}

/** Valor da célula em centavos (com sinal). Número: arredondado a 2 casas (HALF_UP); texto: formato brasileiro. */
export function readAmount(c: Cell): Cents | null {
  if (!c) return null
  if (c.t === 'n' && typeof c.v === 'number') return numberToCents(c.v)
  let s = text(c).replaceAll('R$', '').replaceAll(' ', '').trim()
  if (s === '') return null
  const negative = s.startsWith('(') && s.endsWith(')')
  s = s.replaceAll('(', '').replaceAll(')', '')
  const value = parseAmount(s)
  if (value == null) return null
  return negative ? -value : value
}

/** Como BigDecimal.valueOf(double).setScale(2, HALF_UP): arredonda sobre a representação decimal do número. */
function numberToCents(v: number): Cents | null {
  if (!Number.isFinite(v)) return null
  return decimalToCents(String(v))
}
