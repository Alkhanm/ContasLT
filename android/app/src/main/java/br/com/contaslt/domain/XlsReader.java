package br.com.contaslt.domain;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitura da primeira aba de um .xls (BIFF8, e o básico do BIFF5) dentro de um contêiner OLE2/CFB. Implementação
 * compacta: só o fluxo Workbook, os textos (SST, LABELSST, LABEL), números (NUMBER, RK, MULRK), BOOLERR, fórmulas
 * com resultado guardado (incluindo o registro STRING) e os formatos (XF/FORMAT) para reconhecer datas.
 */
final class XlsReader {

    private static final int END_OF_CHAIN = -2;
    private static final int FREE = -1;

    private XlsReader() {
    }

    // ---------- OLE2 / Compound File ----------

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static final class Cfb {
        final byte[] data;
        final int sectorSize;
        final int miniSectorSize;
        final int miniCutoff;
        final int[] fat;
        int[] miniFat = new int[0];
        byte[] miniStream = new byte[0];

        Cfb(byte[] data) {
            this.data = data;
            if (data.length < 512) {
                throw new IllegalArgumentException("arquivo .xls incompleto");
            }
            sectorSize = 1 << u16(data, 0x1E);
            miniSectorSize = 1 << u16(data, 0x20);
            if (sectorSize < 128 || sectorSize > 65536 || miniSectorSize > sectorSize) {
                throw new IllegalArgumentException("arquivo .xls corrompido");
            }
            int numFat = i32(data, 0x2C);
            miniCutoff = i32(data, 0x38);
            int difatStart = i32(data, 0x44);
            int numDifat = i32(data, 0x48);
            List<Integer> fatSectors = new ArrayList<>();
            for (int i = 0; i < 109 && fatSectors.size() < numFat; i++) {
                int s = i32(data, 0x4C + i * 4);
                if (s >= 0) {
                    fatSectors.add(s);
                }
            }
            int per = sectorSize / 4 - 1;
            int next = difatStart;
            for (int d = 0; d < numDifat && next >= 0 && fatSectors.size() < numFat; d++) {
                int off = offset(next);
                for (int i = 0; i < per && fatSectors.size() < numFat; i++) {
                    int s = i32(data, off + i * 4);
                    if (s >= 0) {
                        fatSectors.add(s);
                    }
                }
                next = i32(data, off + per * 4);
            }
            int perSector = sectorSize / 4;
            fat = new int[fatSectors.size() * perSector];
            for (int f = 0; f < fatSectors.size(); f++) {
                int off = offset(fatSectors.get(f));
                for (int i = 0; i < perSector; i++) {
                    fat[f * perSector + i] = off + i * 4 + 4 <= data.length ? i32(data, off + i * 4) : FREE;
                }
            }
        }

        int offset(int sector) {
            long off = (long) (sector + 1) * sectorSize;
            if (sector < 0 || off >= data.length) {
                throw new IllegalArgumentException("arquivo .xls corrompido");
            }
            return (int) off;
        }

        /** Conteúdo da cadeia de setores a partir de {@code start}; {@code size} < 0 = a cadeia inteira. */
        byte[] stream(int start, long size) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int s = start;
            int guard = 0;
            while (s >= 0 && s != END_OF_CHAIN && guard++ <= fat.length) {
                int off = offset(s);
                int n = Math.min(sectorSize, data.length - off);
                if (n <= 0) {
                    break;
                }
                out.write(data, off, n);
                if (size >= 0 && out.size() >= size) {
                    break;
                }
                s = s < fat.length ? fat[s] : END_OF_CHAIN;
            }
            byte[] b = out.toByteArray();
            if (size >= 0 && b.length > size) {
                byte[] t = new byte[(int) size];
                System.arraycopy(b, 0, t, 0, (int) size);
                return t;
            }
            return b;
        }

        byte[] miniStream(int start, long size) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int s = start;
            int guard = 0;
            while (s >= 0 && s != END_OF_CHAIN && guard++ <= miniFat.length && out.size() < size) {
                int off = s * miniSectorSize;
                if (off >= miniStream.length) {
                    break;
                }
                out.write(miniStream, off, Math.min(miniSectorSize, miniStream.length - off));
                s = s < miniFat.length ? miniFat[s] : END_OF_CHAIN;
            }
            byte[] b = out.toByteArray();
            if (b.length > size) {
                byte[] t = new byte[(int) size];
                System.arraycopy(b, 0, t, 0, (int) size);
                return t;
            }
            return b;
        }
    }

    /** Fluxo "Workbook" (BIFF8) ou "Book" (BIFF5) do arquivo. */
    static byte[] workbookStream(byte[] bytes) {
        Cfb cfb = new Cfb(bytes);
        byte[] dir = cfb.stream(i32(bytes, 0x30), -1);
        int rootStart = -1;
        long rootSize = 0;
        int wbStart = -1;
        long wbSize = 0;
        int bookStart = -1;
        long bookSize = 0;
        for (int off = 0; off + 128 <= dir.length; off += 128) {
            int nameLen = u16(dir, off + 0x40);
            int type = dir[off + 0x42] & 0xFF;
            String name = nameLen >= 2 && nameLen <= 64
                ? new String(dir, off, nameLen - 2, StandardCharsets.UTF_16LE) : "";
            int start = i32(dir, off + 0x74);
            long size = i32(dir, off + 0x78) & 0xFFFFFFFFL;
            if (type == 5) {
                rootStart = start;
                rootSize = size;
            } else if (type == 2 && name.equalsIgnoreCase("Workbook") && wbStart < 0) {
                wbStart = start;
                wbSize = size;
            } else if (type == 2 && name.equalsIgnoreCase("Book") && bookStart < 0) {
                bookStart = start;
                bookSize = size;
            }
        }
        if (wbStart < 0) {
            wbStart = bookStart;
            wbSize = bookSize;
        }
        if (wbStart < 0) {
            throw new IllegalArgumentException("o arquivo .xls não tem a pasta de trabalho (Workbook)");
        }
        if (wbSize < cfb.miniCutoff) {
            cfb.miniStream = cfb.stream(rootStart, rootSize);
            byte[] mf = cfb.stream(i32(bytes, 0x3C), -1);
            int count = Math.max(0, i32(bytes, 0x40)) * (cfb.sectorSize / 4);
            cfb.miniFat = new int[Math.min(count, mf.length / 4)];
            for (int i = 0; i < cfb.miniFat.length; i++) {
                cfb.miniFat[i] = i32(mf, i * 4);
            }
            return cfb.miniStream(wbStart, wbSize);
        }
        return cfb.stream(wbStart, wbSize);
    }

    // ---------- BIFF ----------

    /** Leitor sobre um registro e seus CONTINUE: ao cruzar o limite no meio de caracteres, lê o novo byte de opções. */
    private static final class Segments {
        final List<byte[]> parts;
        int part;
        int pos;

        Segments(List<byte[]> parts) {
            this.parts = parts;
        }

        private void advance() {
            while (part < parts.size() && pos >= parts.get(part).length) {
                part++;
                pos = 0;
            }
            if (part >= parts.size()) {
                throw new IllegalArgumentException("registro de textos incompleto");
            }
        }

        int u8() {
            advance();
            return parts.get(part)[pos++] & 0xFF;
        }

        int u16() {
            int a = u8();
            return a | (u8() << 8);
        }

        int i32() {
            int a = u16();
            return a | (u16() << 16);
        }

        void skip(long n) {
            while (n > 0) {
                advance();
                int avail = parts.get(part).length - pos;
                int k = (int) Math.min(avail, n);
                pos += k;
                n -= k;
            }
        }

        String chars(int cch, boolean high) {
            StringBuilder sb = new StringBuilder(cch);
            boolean h = high;
            while (sb.length() < cch) {
                if (part < parts.size() && pos >= parts.get(part).length) {
                    // Continuação no meio do texto: novo byte de opções no início do CONTINUE.
                    part++;
                    pos = 0;
                    if (part >= parts.size()) {
                        break;
                    }
                    h = (u8() & 1) != 0;
                    continue;
                }
                if (part >= parts.size()) {
                    break;
                }
                byte[] p = parts.get(part);
                if (h) {
                    if (pos + 1 >= p.length) {
                        break;
                    }
                    sb.append((char) ((p[pos] & 0xFF) | ((p[pos + 1] & 0xFF) << 8)));
                    pos += 2;
                } else {
                    sb.append((char) (p[pos] & 0xFF));
                    pos++;
                }
            }
            return sb.toString();
        }

        /** XLUnicodeString (BIFF8): opções e caracteres, sem formatação. */
        String unicodeStringNoRuns(int cch) {
            return chars(cch, (u8() & 0x01) != 0);
        }

        /** XLUnicodeRichExtendedString (BIFF8). */
        String unicodeString(int cch) {
            int flags = u8();
            boolean high = (flags & 0x01) != 0;
            int runs = (flags & 0x08) != 0 ? u16() : 0;
            long ext = (flags & 0x04) != 0 ? (i32() & 0xFFFFFFFFL) : 0;
            String s = chars(cch, high);
            skip(4L * runs);
            skip(ext);
            return s;
        }
    }

    private static Segments single(byte[] data, int from) {
        byte[] p = new byte[Math.max(0, data.length - from)];
        System.arraycopy(data, from, p, 0, p.length);
        List<byte[]> parts = new ArrayList<>();
        parts.add(p);
        return new Segments(parts);
    }

    private static final class Record {
        final int type;
        final byte[] data;
        final int next;

        Record(int type, byte[] data, int next) {
            this.type = type;
            this.data = data;
            this.next = next;
        }
    }

    private static Record record(byte[] wb, int pos) {
        if (pos + 4 > wb.length) {
            return null;
        }
        int type = u16(wb, pos);
        int len = u16(wb, pos + 2);
        int end = Math.min(wb.length, pos + 4 + len);
        byte[] data = new byte[end - pos - 4];
        System.arraycopy(wb, pos + 4, data, 0, data.length);
        return new Record(type, data, pos + 4 + len);
    }

    private static final int BOF = 0x0809;
    private static final int EOF = 0x000A;
    private static final int CONTINUE = 0x003C;

    static Excel.Sheet read(byte[] bytes) {
        byte[] wb = workbookStream(bytes);
        Record bof = record(wb, 0);
        if (bof == null || (bof.type & 0xFF) != 0x09 || bof.data.length < 2) {
            throw new IllegalArgumentException("formato de .xls não suportado");
        }
        int version = u16(bof.data, 0);
        boolean biff8 = version == 0x0600;
        if (!biff8 && version != 0x0500) {
            throw new IllegalArgumentException("formato de .xls antigo demais (só Excel 97 ou mais novo)");
        }

        Charset codepage = Charset.forName("windows-1252");
        Map<Integer, String> formats = new HashMap<>();
        List<Integer> xfs = new ArrayList<>();
        List<String> sst = new ArrayList<>();
        boolean date1904 = false;
        int sheetOffset = -1;

        int pos = bof.next;
        while (true) {
            Record r = record(wb, pos);
            if (r == null || r.type == EOF) {
                break;
            }
            pos = r.next;
            byte[] d = r.data;
            switch (r.type) {
                case 0x002F:
                    throw new IllegalArgumentException("a planilha está protegida por senha");
                case 0x0042: { // CODEPAGE
                    int cp = d.length >= 2 ? u16(d, 0) : 1252;
                    if (cp != 1200 && cp != 1252) {
                        try {
                            codepage = Charset.forName("windows-" + cp);
                        } catch (RuntimeException e) {
                            try {
                                codepage = Charset.forName("Cp" + cp);
                            } catch (RuntimeException ignored) {
                                codepage = Charset.forName("windows-1252");
                            }
                        }
                    }
                    break;
                }
                case 0x0085: // BOUNDSHEET
                    if (sheetOffset < 0 && d.length >= 4) {
                        sheetOffset = i32(d, 0);
                    }
                    break;
                case 0x041E: // FORMAT
                    if (d.length >= 3) {
                        int idx = u16(d, 0);
                        formats.put(idx, biff8 ? single(d, 4).unicodeStringNoRuns(u16(d, 2)) : byteString(d, 3, d[2] & 0xFF, codepage));
                    }
                    break;
                case 0x00E0: // XF
                    if (d.length >= 4) {
                        xfs.add(u16(d, 2));
                    }
                    break;
                case 0x0022: // DATEMODE
                    date1904 = d.length >= 2 && u16(d, 0) == 1;
                    break;
                case 0x00FC: { // SST + CONTINUE
                    List<byte[]> parts = new ArrayList<>();
                    byte[] first = new byte[Math.max(0, d.length - 8)];
                    System.arraycopy(d, Math.min(8, d.length), first, 0, first.length);
                    parts.add(first);
                    int unique = d.length >= 8 ? i32(d, 4) : 0;
                    Record c = record(wb, pos);
                    while (c != null && c.type == CONTINUE) {
                        parts.add(c.data);
                        pos = c.next;
                        c = record(wb, pos);
                    }
                    Segments seg = new Segments(parts);
                    try {
                        for (int i = 0; i < unique; i++) {
                            sst.add(seg.unicodeString(seg.u16()));
                        }
                    } catch (IllegalArgumentException ignored) {
                        // tabela truncada: fica com o que foi lido
                    }
                    break;
                }
                default:
                    break;
            }
        }
        if (sheetOffset < 0 || sheetOffset >= wb.length) {
            throw new IllegalArgumentException("a planilha não tem abas");
        }

        Excel.Sheet out = new Excel.Sheet();
        out.date1904 = date1904;
        pos = sheetOffset;
        int depth = 0;
        int pendingRow = -1;
        int pendingCol = -1;
        while (true) {
            Record r = record(wb, pos);
            if (r == null) {
                break;
            }
            pos = r.next;
            byte[] d = r.data;
            if (r.type == BOF) {
                depth++;
                continue;
            }
            if (r.type == EOF) {
                if (--depth <= 0) {
                    break;
                }
                continue;
            }
            if (depth != 1) {
                continue;
            }
            switch (r.type) {
                case 0x0200: // DIMENSIONS
                    if (biff8 && d.length >= 12) {
                        int rwMac = i32(d, 4);
                        int colMac = u16(d, 10);
                        if (rwMac > 0 && colMac > 0) {
                            out.extend(i32(d, 0), u16(d, 8), rwMac - 1, colMac - 1);
                        }
                    } else if (!biff8 && d.length >= 8) {
                        int rwMac = u16(d, 2);
                        int colMac = u16(d, 6);
                        if (rwMac > 0 && colMac > 0) {
                            out.extend(u16(d, 0), u16(d, 4), rwMac - 1, colMac - 1);
                        }
                    }
                    break;
                case 0x00FD: // LABELSST
                    if (d.length >= 10) {
                        int isst = i32(d, 6);
                        out.put(u16(d, 0), u16(d, 2), Excel.Cell.string(isst >= 0 && isst < sst.size() ? sst.get(isst) : ""));
                    }
                    break;
                case 0x0204: // LABEL
                case 0x00D6: // RSTRING
                    if (d.length >= 8) {
                        int cch = u16(d, 6);
                        String s = biff8 ? single(d, 8).unicodeStringNoRuns(cch) : byteString(d, 8, cch, codepage);
                        out.put(u16(d, 0), u16(d, 2), Excel.Cell.string(s));
                    }
                    break;
                case 0x0203: // NUMBER
                    if (d.length >= 14) {
                        out.put(u16(d, 0), u16(d, 2), Excel.Cell.number(dbl(d, 6), format(u16(d, 4), xfs, formats)));
                    }
                    break;
                case 0x027E: // RK
                    if (d.length >= 10) {
                        out.put(u16(d, 0), u16(d, 2), Excel.Cell.number(rk(i32(d, 6)), format(u16(d, 4), xfs, formats)));
                    }
                    break;
                case 0x00BD: { // MULRK
                    if (d.length >= 6) {
                        int row = u16(d, 0);
                        int col = u16(d, 2);
                        int n = (d.length - 6) / 6;
                        for (int i = 0; i < n; i++) {
                            int off = 4 + i * 6;
                            out.put(row, col + i, Excel.Cell.number(rk(i32(d, off + 2)), format(u16(d, off), xfs, formats)));
                        }
                    }
                    break;
                }
                case 0x0205: // BOOLERR
                    if (d.length >= 8) {
                        int v = d[6] & 0xFF;
                        out.put(u16(d, 0), u16(d, 2), d[7] != 0 ? Excel.Cell.error(errorText(v)) : Excel.Cell.bool(v != 0));
                    }
                    break;
                case 0x0006: { // FORMULA
                    if (d.length >= 14) {
                        int row = u16(d, 0);
                        int col = u16(d, 2);
                        if (u16(d, 12) == 0xFFFF) {
                            int kind = d[6] & 0xFF;
                            if (kind == 0) {
                                pendingRow = row;
                                pendingCol = col;
                            } else if (kind == 1) {
                                out.put(row, col, Excel.Cell.bool(d[8] != 0));
                            } else if (kind == 2) {
                                out.put(row, col, Excel.Cell.error(errorText(d[8] & 0xFF)));
                            } else if (kind == 3) {
                                out.put(row, col, Excel.Cell.string(""));
                            }
                        } else {
                            out.put(row, col, Excel.Cell.number(dbl(d, 6), format(u16(d, 4), xfs, formats)));
                        }
                    }
                    break;
                }
                case 0x0207: // STRING (resultado da fórmula anterior)
                    if (pendingRow >= 0 && d.length >= 2) {
                        int cch = u16(d, 0);
                        String s = biff8 ? single(d, 2).unicodeStringNoRuns(cch) : byteString(d, 2, cch, codepage);
                        out.put(pendingRow, pendingCol, Excel.Cell.string(s));
                    }
                    pendingRow = -1;
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    private static String format(int xf, List<Integer> xfs, Map<Integer, String> formats) {
        int id = xf >= 0 && xf < xfs.size() ? xfs.get(xf) : 0;
        String custom = formats.get(id);
        return custom != null ? custom : Excel.builtinFormat(id);
    }

    private static double dbl(byte[] d, int off) {
        long bits = (i32(d, off) & 0xFFFFFFFFL) | ((long) i32(d, off + 4) << 32);
        return Double.longBitsToDouble(bits);
    }

    /** Número compactado RK. */
    static double rk(int rk) {
        double v;
        if ((rk & 0x02) != 0) {
            v = rk >> 2;
        } else {
            v = Double.longBitsToDouble(((long) (rk & 0xFFFFFFFC)) << 32);
        }
        return (rk & 0x01) != 0 ? v / 100 : v;
    }

    private static String byteString(byte[] d, int off, int len, Charset cs) {
        int n = Math.max(0, Math.min(len, d.length - off));
        return new String(d, off, n, cs);
    }

    private static String errorText(int code) {
        switch (code) {
            case 0x00: return "#NULL!";
            case 0x07: return "#DIV/0!";
            case 0x0F: return "#VALUE!";
            case 0x17: return "#REF!";
            case 0x1D: return "#NAME?";
            case 0x24: return "#NUM!";
            case 0x2A: return "#N/A";
            default: return "#ERR!";
        }
    }
}
