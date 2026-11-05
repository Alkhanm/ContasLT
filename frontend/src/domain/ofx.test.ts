import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { parseAmount, parseOfx, ParseError } from './ofx'

/** Porte de OfxParserTest. */
const sample = () => new Uint8Array(readFileSync(new URL('../../../backend/src/test/resources/samples/extrato.ofx', import.meta.url)))

describe('OfxParser', () => {
  it('lê extrato SGML com vírgula decimal brasileira', () => {
    const rows = parseOfx(sample())
    expect(rows).toHaveLength(3)
    const energia = rows[0]
    expect(energia.date).toBe('2026-10-03')
    expect(energia.amount).toBe(18990)
    expect(energia.type).toBe('EXPENSE')
    expect(energia.description).toBe('PAGTO ENEL DISTRIBUICAO')
    expect(energia.externalId).toBe('2026100300001')
    expect(rows[1].type).toBe('INCOME')
    expect(rows[2].description).toBe('IFOOD *RESTAURANTE')
  })

  it('interpreta os formatos de valor (em centavos)', () => {
    expect(parseAmount('-1.234,56')).toBe(-123456)
    expect(parseAmount('1,234.56')).toBe(123456)
    expect(parseAmount('-50,00')).toBe(-5000)
    expect(parseAmount('4500.00')).toBe(450000)
    expect(parseAmount('−10,005')).toBe(-1001)
    expect(parseAmount('abc')).toBeNull()
  })

  it('XML 2.x em UTF-8, com tags fechadas e acentos', () => {
    const xml = '<?xml version="1.0" encoding="UTF-8"?><OFX><BANKTRANLIST>'
      + '<STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>20260105</DTPOSTED><TRNAMT>-12.50</TRNAMT><FITID>X1</FITID>'
      + '<NAME>Padaria   São   João</NAME></STMTTRN></BANKTRANLIST></OFX>'
    const rows = parseOfx(new TextEncoder().encode(xml))
    expect(rows).toEqual([{ date: '2026-01-05', description: 'Padaria São João', amount: 1250, type: 'EXPENSE', externalId: 'X1', categoryHint: null }])
  })

  it('CHARSET:1252 decodifica como windows-1252', () => {
    const text = 'OFXHEADER:100\nCHARSET:1252\n<OFX>\n<STMTTRN>\n<DTPOSTED>20260105\n<TRNAMT>10\n<MEMO>CAFÉ\n</STMTTRN>\n</OFX>'
    const bytes = Uint8Array.from([...text].map((c) => c.charCodeAt(0)))
    const rows = parseOfx(bytes)
    expect(rows[0].description).toBe('CAFÉ')
    expect(rows[0].type).toBe('INCOME')
    expect(rows[0].externalId).toBeNull()
  })

  it('recusa arquivo que não é OFX ou sem lançamentos', () => {
    expect(() => parseOfx(new TextEncoder().encode('oi'))).toThrow(ParseError)
    expect(() => parseOfx(new TextEncoder().encode('oi'))).toThrow('O arquivo não parece ser um extrato OFX válido')
    expect(() => parseOfx(new TextEncoder().encode('<OFX></OFX>'))).toThrow('Nenhum lançamento encontrado no arquivo OFX')
  })
})
