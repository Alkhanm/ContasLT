import type { TransactionType } from '@/data/schema'
import { lengthOfMonth, ymd, type IsoDate } from './dates'
import type { Cents } from './money'
import { truncate } from './text'

/**
 * Leitor de extratos OFX (SGML 1.x e XML 2.x), porte de OfxParser. Trabalha por expressões regulares sobre os
 * blocos STMTTRN, o que tolera os arquivos "quase válidos" que os bancos brasileiros costumam gerar.
 */

/** Linha extraída de um arquivo, antes de virar lançamento (porte de ParsedRow). Valor sempre positivo, em centavos. */
export interface ParsedRow {
  date: IsoDate
  description: string
  amount: Cents
  type: TransactionType
  externalId: string | null
  categoryHint: string | null
}

/** Arquivo que não dá para ler: a mensagem vai para o usuário (o serviço a transforma em BusinessError). */
export class ParseError extends Error {}

export type Bytes = Uint8Array | ArrayBuffer

export const toBytes = (b: Bytes): Uint8Array => (b instanceof Uint8Array ? b : new Uint8Array(b))

const TRANSACTION = /<STMTTRN>([\s\S]*?)(<\/STMTTRN>|(?=<STMTTRN>)|$)/gi
/** `\s` do Java (sem os espaços Unicode do JavaScript). */
const JAVA_SPACES = /[ \t\n\x0B\f\r]+/g

export function parseOfx(input: Bytes): ParsedRow[] {
  const text = decode(toBytes(input))
  if (!text.toUpperCase().includes('<OFX')) {
    throw new ParseError('O arquivo não parece ser um extrato OFX válido')
  }
  const rows: ParsedRow[] = []
  for (const m of text.matchAll(TRANSACTION)) {
    const block = m[1]
    const amountText = tag(block, 'TRNAMT')
    const dateText = tag(block, 'DTPOSTED')
    if (amountText == null || dateText == null || dateText.length < 8) continue
    const amount = parseAmount(amountText)
    if (amount == null || amount === 0) continue
    const date = parseOfxDate(dateText.slice(0, 8))
    const memo = tag(block, 'MEMO')
    const name = tag(block, 'NAME')
    let description = memo != null && memo.trim() !== '' ? memo : name
    if (description == null || description.trim() === '') {
      description = tag(block, 'TRNTYPE') ?? 'Lançamento'
    }
    const fitId = tag(block, 'FITID')
    rows.push({
      date,
      description: truncate(description.replace(JAVA_SPACES, ' '), 140),
      amount: Math.abs(amount),
      type: amount < 0 ? 'EXPENSE' : 'INCOME',
      externalId: fitId == null || fitId.trim() === '' ? null : truncate(fitId, 120),
      categoryHint: null,
    })
  }
  if (!rows.length) throw new ParseError('Nenhum lançamento encontrado no arquivo OFX')
  return rows
}

/** `AAAAMMDD`, como LocalDate.parse com o padrão yyyyMMdd. */
function parseOfxDate(s: string): IsoDate {
  const m = /^(\d{4})(\d{2})(\d{2})$/.exec(s)
  const date = m ? smartDate(Number(m[1]), Number(m[2]), Number(m[3])) : null
  if (!date) throw new ParseError(`Data inválida no arquivo OFX: "${s}"`)
  return date
}

/**
 * Data como o ResolverStyle.SMART do java.time: mês 1–12 e dia 1–31; dia além do fim do mês vira o último dia
 * (31/02 → 28 ou 29/02). `null` se fora disso.
 */
export function smartDate(y: number, mo: number, d: number): IsoDate | null {
  if (mo < 1 || mo > 12 || d < 1 || d > 31) return null
  return ymd(y, mo, Math.min(d, lengthOfMonth(y, mo)))
}

/**
 * Valor em formato brasileiro ou americano ("-1.234,56", "1,234.56", "-50,00", "4500.00"), em centavos com
 * arredondamento HALF_UP. `null` se não for um número.
 */
export function parseAmount(raw: string): Cents | null {
  let s = raw.trim().replaceAll(' ', '').replaceAll('−', '-')
  if (s.includes(',') && s.includes('.')) {
    s = s.lastIndexOf(',') > s.lastIndexOf('.') ? s.replaceAll('.', '').replaceAll(',', '.') : s.replaceAll(',', '')
  } else if (s.includes(',')) {
    s = s.replaceAll(',', '.')
  }
  return decimalToCents(s)
}

/** Texto no formato aceito por `new BigDecimal(String)` → centavos (HALF_UP), sem passar por ponto flutuante. */
export function decimalToCents(s: string): Cents | null {
  const m = /^([+-]?)(?:(\d+)(?:\.(\d*))?|\.(\d+))(?:[eE]([+-]?\d+))?$/.exec(s)
  if (!m) return null
  const intPart = m[2] ?? ''
  const fracPart = m[3] ?? m[4] ?? ''
  const exp = m[5] ? Number(m[5]) : 0
  if (Math.abs(exp) > 30) return null
  const digits = BigInt((intPart + fracPart) || '0')
  // valor = digits × 10^(exp − casas); centavos = valor × 100
  const scale = fracPart.length - exp - 2
  let cents: bigint
  if (scale <= 0) {
    cents = digits * 10n ** BigInt(-scale)
  } else {
    const div = 10n ** BigInt(scale)
    cents = digits / div
    if ((digits % div) * 2n >= div) cents += 1n
  }
  const n = Number(cents)
  if (!Number.isSafeInteger(n)) return null
  return m[1] === '-' ? -n : n
}

function tag(block: string, name: string): string | null {
  const m = new RegExp(`<${name}>\\s*([^<\\r\\n]*)`, 'i').exec(block)
  return m ? m[1].trim() : null
}

/** ISO-8859-1 exato: cada byte vira o caractere de mesmo código. */
export function latin1(bytes: Uint8Array, max = bytes.length): string {
  let s = ''
  const end = Math.min(bytes.length, max)
  for (let i = 0; i < end; i++) s += String.fromCharCode(bytes[i])
  return s
}

function decode(bytes: Uint8Array): string {
  const header = latin1(bytes, 600).toUpperCase()
  if (header.includes('CHARSET:1252') || header.includes('ENCODING:USASCII') || header.includes('ENCODING="ISO-8859-1"')
    || header.includes('ENCODING="WINDOWS-1252"')) {
    return new TextDecoder('windows-1252').decode(bytes)
  }
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    return new TextDecoder('windows-1252').decode(bytes)
  }
}
