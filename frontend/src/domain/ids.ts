/**
 * Ids dos registros: UUID v4 aleatório para o que o usuário cria e UUID v5 (SHA-1, RFC 4122) para o que precisa
 * dar o mesmo id em qualquer aparelho, como o vencimento de uma recorrente numa data. O app Android usa o mesmo
 * namespace e o mesmo nome, então os dois geram ids iguais.
 */

export function newId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const b = new Uint8Array(16)
  crypto.getRandomValues(b)
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  return format(b)
}

/** Namespace fixo do ContasLT (não mude: ids de vencimentos já gravados dependem dele). */
export const CONTASLT_NAMESPACE = '6f1c2a4e-9b7d-4c3e-8a51-2d0b7e6f9c13'

export function uuidV5(name: string, namespace = CONTASLT_NAMESPACE): string {
  const ns = parse(namespace)
  const nameBytes = new TextEncoder().encode(name)
  const data = new Uint8Array(ns.length + nameBytes.length)
  data.set(ns)
  data.set(nameBytes, ns.length)
  const hash = sha1(data)
  const b = hash.slice(0, 16)
  b[6] = (b[6] & 0x0f) | 0x50
  b[8] = (b[8] & 0x3f) | 0x80
  return format(b)
}

/** Id do vencimento de uma conta recorrente na data prevista pela regra. */
export function occurrenceId(billId: string, scheduledDate: string) {
  return uuidV5(`occurrence:${billId}:${scheduledDate}`)
}

function parse(uuid: string): Uint8Array {
  const hex = uuid.replace(/-/g, '')
  const out = new Uint8Array(16)
  for (let i = 0; i < 16; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16)
  return out
}

function format(b: Uint8Array): string {
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

/** SHA-1 síncrono (o crypto.subtle é assíncrono, e o gerador de vencimentos roda dentro de leituras síncronas). */
export function sha1(msg: Uint8Array): Uint8Array {
  const ml = msg.length
  const withPad = new Uint8Array(((ml + 9 + 63) >> 6) << 6)
  withPad.set(msg)
  withPad[ml] = 0x80
  const view = new DataView(withPad.buffer)
  view.setUint32(withPad.length - 4, (ml * 8) >>> 0)
  view.setUint32(withPad.length - 8, Math.floor((ml * 8) / 2 ** 32))
  let h0 = 0x67452301, h1 = 0xefcdab89, h2 = 0x98badcfe, h3 = 0x10325476, h4 = 0xc3d2e1f0
  const w = new Uint32Array(80)
  for (let off = 0; off < withPad.length; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(off + i * 4)
    for (let i = 16; i < 80; i++) {
      const x = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16]
      w[i] = (x << 1) | (x >>> 31)
    }
    let a = h0, b = h1, c = h2, d = h3, e = h4
    for (let i = 0; i < 80; i++) {
      let f: number, k: number
      if (i < 20) { f = (b & c) | (~b & d); k = 0x5a827999 }
      else if (i < 40) { f = b ^ c ^ d; k = 0x6ed9eba1 }
      else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8f1bbcdc }
      else { f = b ^ c ^ d; k = 0xca62c1d6 }
      const t = (((a << 5) | (a >>> 27)) + f + e + k + w[i]) >>> 0
      e = d; d = c; c = (b << 30) | (b >>> 2); b = a; a = t
    }
    h0 = (h0 + a) >>> 0; h1 = (h1 + b) >>> 0; h2 = (h2 + c) >>> 0; h3 = (h3 + d) >>> 0; h4 = (h4 + e) >>> 0
  }
  const out = new Uint8Array(20)
  const ov = new DataView(out.buffer)
  ;[h0, h1, h2, h3, h4].forEach((h, i) => ov.setUint32(i * 4, h))
  return out
}
