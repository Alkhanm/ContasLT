import { describe, expect, it } from 'vitest'
import { utils, write, type CellObject } from 'xlsx'
import { parseExcel } from './excel'

type Value = string | number | CellObject | null

/** Planilha em memória (primeira aba = linhas dadas). */
function workbook(rows: Value[][], bookType: 'xlsx' | 'biff8' = 'xlsx'): Uint8Array {
  const ws = utils.aoa_to_sheet(rows)
  const wb = utils.book_new()
  utils.book_append_sheet(wb, ws, 'Extrato')
  return new Uint8Array(write(wb, { type: 'array', bookType }) as ArrayBuffer)
}

/** Data como célula numérica formatada (serial do Excel, sistema 1900). */
function dateCell(iso: string): CellObject {
  const serial = Date.UTC(Number(iso.slice(0, 4)), Number(iso.slice(5, 7)) - 1, Number(iso.slice(8, 10))) / 86_400_000 + 25569
  return { t: 'n', v: serial, z: 'dd/mm/yyyy' }
}

describe('ExcelParser', () => {
  it('acha o cabeçalho depois de linhas de título e lê tipo e categoria', () => {
    const bytes = workbook([
      ['Extrato da conta'],
      [],
      ['Data', 'Histórico', 'Valor (R$)', 'Tipo', 'Categoria'],
      [dateCell('2026-03-05'), 'Supermercado   Bom   Preço', -120.555, null, 'Mercado'],
      ['06/03/2026', 'Salário', '4.500,00', 'Crédito', ''],
      ['7/3/2026', 'Estorno', 'R$ (30,00)', null, null],
      ['08/03/26', 'Taxa', '15,90', 'Débito', null],
      ['2026-03-09', 'Zero', 0, null, null],
      ['sem data', 'Ignorada', 10, null, null],
    ])
    const rows = parseExcel(bytes)
    expect(rows).toEqual([
      { date: '2026-03-05', description: 'Supermercado Bom Preço', amount: 12056, type: 'EXPENSE', externalId: null, categoryHint: 'Mercado' },
      { date: '2026-03-06', description: 'Salário', amount: 450000, type: 'INCOME', externalId: null, categoryHint: null },
      { date: '2026-03-07', description: 'Estorno', amount: 3000, type: 'EXPENSE', externalId: null, categoryHint: null },
      { date: '2026-03-08', description: 'Taxa', amount: 1590, type: 'EXPENSE', externalId: null, categoryHint: null },
    ])
  })

  it('sem cabeçalho assume data | descrição | valor; também lê .xls', () => {
    for (const type of ['xlsx', 'biff8'] as const) {
      const rows = parseExcel(workbook([['31/02/2026', '', 10]], type))
      // Dia além do fim do mês vira o último dia (como o java.time); descrição vazia vira "Lançamento".
      expect(rows).toEqual([{ date: '2026-02-28', description: 'Lançamento', amount: 1000, type: 'INCOME', externalId: null, categoryHint: null }])
    }
  })

  it('cabeçalho sem coluna de descrição usa a primeira coluna livre', () => {
    const rows = parseExcel(workbook([['Valor', 'Data', 'Loja'], [-5, '01/01/2026', 'Banca']]))
    expect(rows[0].description).toBe('Banca')
    expect(rows[0].amount).toBe(500)
  })

  it('mensagens de erro', () => {
    expect(() => parseExcel(workbook([['Data', 'Descrição', 'Valor']])))
      .toThrow('Nenhuma linha com data e valor foi encontrada na planilha. Use colunas Data, Descrição e Valor.')
    expect(() => parseExcel(new TextEncoder().encode('não é planilha'))).toThrow(/^Não foi possível ler a planilha: /)
  })
})
