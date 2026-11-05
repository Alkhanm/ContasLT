/** Minúsculas, sem acentos, sem dígitos/pontuação e com espaços colapsados (como TextNormalizer do backend). */
export function normalize(text: string | null | undefined): string {
  if (!text) return ''
  return text
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(/[^a-z\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

export function truncate(text: string, max: number): string
export function truncate(text: string | null | undefined, max: number): string | null
export function truncate(text: string | null | undefined, max: number): string | null {
  if (text == null) return null
  const t = text.trim()
  return t.length <= max ? t : t.slice(0, max)
}

export function blankToNull(s: string | null | undefined): string | null {
  return s == null || s.trim() === '' ? null : s.trim()
}

export const isBlank = (s: string | null | undefined) => s == null || s.trim() === ''
