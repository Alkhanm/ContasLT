import { describe, expect, it } from 'vitest'
import { occurrenceId, sha1, uuidV5 } from './ids'

describe('ids', () => {
  it('UUID v5 igual ao da RFC (exemplo do namespace DNS)', () => {
    expect(uuidV5('www.example.com', '6ba7b810-9dad-11d1-80b4-00c04fd430c8')).toBe('2ed6657d-e927-568b-95e1-2665a8aea6a2')
  })
  it('SHA-1 de "abc"', () => {
    const hex = [...sha1(new TextEncoder().encode('abc'))].map((b) => b.toString(16).padStart(2, '0')).join('')
    expect(hex).toBe('a9993e364706816aba3e25717850c26c9cd0d89d')
  })
  it('vencimento: mesmo id para a mesma regra e data', () => {
    expect(occurrenceId('b1', '2026-01-05')).toBe(occurrenceId('b1', '2026-01-05'))
    expect(occurrenceId('b1', '2026-01-05')).not.toBe(occurrenceId('b1', '2026-02-05'))
  })
})
