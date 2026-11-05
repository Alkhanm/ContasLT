package br.com.contaslt.domain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Planilhas montadas nos testes (o PWA usa o SheetJS para escrevê-las): .xlsx com java.util.zip e um .xls BIFF8
 * mínimo dentro de um contêiner OLE2 escrito à mão.
 */
final class SpreadsheetFixtures {

    private SpreadsheetFixtures() {
    }

    /** Data como célula numérica formatada (serial do Excel, sistema 1900). */
    static final class DateCell {
        final double serial;

        DateCell(String iso) {
            serial = LocalDate.parse(iso).toEpochDay() + 25569;
        }
    }

    /** Texto em linha (inlineStr), em vez de texto compartilhado. */
    static final class Inline {
        final String text;

        Inline(String text) {
            this.text = text;
        }
    }

    static Object[] row(Object... cells) {
        return cells;
    }

    static List<Object[]> rows(Object[]... rows) {
        return new ArrayList<>(java.util.Arrays.asList(rows));
    }

    // ---------- .xlsx ----------

    static byte[] xlsx(List<Object[]> rows) {
        return xlsx(rows, false, true);
    }

    static byte[] xlsx(List<Object[]> rows, boolean date1904, boolean withDimension) {
        Map<String, Integer> shared = new LinkedHashMap<>();
        StringBuilder sheet = new StringBuilder();
        sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
            .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
        int maxCol = 0;
        for (Object[] r : rows) {
            maxCol = Math.max(maxCol, r.length);
        }
        if (withDimension && !rows.isEmpty()) {
            sheet.append("<dimension ref=\"A1:").append(col(Math.max(0, maxCol - 1))).append(rows.size()).append("\"/>");
        }
        sheet.append("<sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            Object[] cells = rows.get(r);
            sheet.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < cells.length; c++) {
                Object v = cells[c];
                if (v == null) {
                    continue;
                }
                String ref = col(c) + (r + 1);
                if (v instanceof String) {
                    Integer idx = shared.get(v);
                    if (idx == null) {
                        idx = shared.size();
                        shared.put((String) v, idx);
                    }
                    sheet.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(idx).append("</v></c>");
                } else if (v instanceof Inline) {
                    sheet.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(esc(((Inline) v).text)).append("</t></is></c>");
                } else if (v instanceof Boolean) {
                    sheet.append("<c r=\"").append(ref).append("\" t=\"b\"><v>").append((Boolean) v ? 1 : 0)
                        .append("</v></c>");
                } else if (v instanceof DateCell) {
                    double serial = ((DateCell) v).serial - (date1904 ? 1462 : 0);
                    sheet.append("<c r=\"").append(ref).append("\" s=\"1\"><v>").append(num(serial)).append("</v></c>");
                } else {
                    sheet.append("<c r=\"").append(ref).append("\"><v>").append(num(((Number) v).doubleValue()))
                        .append("</v></c>");
                }
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");

        StringBuilder sst = new StringBuilder();
        sst.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            .append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"")
            .append(shared.size()).append("\" uniqueCount=\"").append(shared.size()).append("\">");
        for (String s : shared.keySet()) {
            sst.append("<si><t xml:space=\"preserve\">").append(esc(s)).append("</t></si>");
        }
        sst.append("</sst>");

        Map<String, String> files = new LinkedHashMap<>();
        files.put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "</Types>");
        files.put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
            + "</Relationships>");
        files.put("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
            + (date1904 ? "<workbookPr date1904=\"1\"/>" : "<workbookPr/>")
            + "<sheets><sheet name=\"Extrato\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Outra\" sheetId=\"2\" r:id=\"rId2\"/></sheets>"
            + "</workbook>");
        files.put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
            + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>"
            + "<Relationship Id=\"rId4\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
            + "</Relationships>");
        files.put("xl/worksheets/sheet1.xml", sheet.toString());
        files.put("xl/worksheets/sheet2.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
            + "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Outra aba</t></is></c></row></sheetData></worksheet>");
        files.put("xl/sharedStrings.xml", sst.toString());
        files.put("xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"dd/mm/yyyy\"/></numFmts>"
            + "<cellXfs count=\"2\"><xf numFmtId=\"0\"/><xf numFmtId=\"164\" applyNumberFormat=\"1\"/></cellXfs>"
            + "</styleSheet>");
        return zip(files);
    }

    static byte[] zip(Map<String, String> files) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(bos)) {
                for (Map.Entry<String, String> e : files.entrySet()) {
                    z.putNextEntry(new ZipEntry(e.getKey()));
                    z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    z.closeEntry();
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String col(int c) {
        StringBuilder sb = new StringBuilder();
        int n = c + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    // ---------- .xls (BIFF8 em OLE2) ----------

    /** Montador de registros BIFF8. */
    static final class Biff {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Biff record(int type, byte[] data) {
            le16(out, type);
            le16(out, data.length);
            out.write(data, 0, data.length);
            return this;
        }

        int size() {
            return out.size();
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    static final class Data {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Data u8(int v) {
            out.write(v & 0xFF);
            return this;
        }

        Data u16(int v) {
            le16(out, v);
            return this;
        }

        Data i32(int v) {
            le16(out, v & 0xFFFF);
            le16(out, (v >>> 16) & 0xFFFF);
            return this;
        }

        Data f64(double v) {
            byte[] b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array();
            out.write(b, 0, 8);
            return this;
        }

        /** Caracteres de um texto: compactados (1 byte) ou UTF-16LE. */
        Data chars(String s, boolean high) {
            for (char c : s.toCharArray()) {
                if (high) {
                    u16(c);
                } else {
                    u8(c);
                }
            }
            return this;
        }

        /** XLUnicodeString: cch, opções, caracteres. */
        Data unicode(String s) {
            boolean high = needsHigh(s);
            return u16(s.length()).u8(high ? 1 : 0).chars(s, high);
        }

        Data zeros(int n) {
            for (int i = 0; i < n; i++) {
                out.write(0);
            }
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    static boolean needsHigh(String s) {
        for (char c : s.toCharArray()) {
            if (c > 0xFF) {
                return true;
            }
        }
        return false;
    }

    static Data data() {
        return new Data();
    }

    static byte[] bof(int dt) {
        return data().u16(0x0600).u16(dt).u16(0x0DBB).u16(0x07CC).i32(0).i32(0x06).bytes();
    }

    /** XF com o formato numérico dado. */
    static byte[] xf(int ifmt) {
        return data().u16(0).u16(ifmt).u16(0).zeros(14).bytes();
    }

    /** RK de inteiro (com /100 opcional). */
    static int rkInt(int v, boolean div100) {
        return (v << 2) | 0x02 | (div100 ? 0x01 : 0);
    }

    /**
     * Pasta de trabalho: globais (FORMAT, XFs, DATEMODE, BOUNDSHEET, SST) e a aba com os registros de célula dados.
     * {@code sstParts}: o SST já dividido em registro + CONTINUEs. {@code padding}: bytes extras nos globais para o
     * fluxo passar de 4096 (setores comuns, sem mini-stream).
     */
    static byte[] workbook(List<byte[]> sstParts, Biff cells, int padding) {
        Biff g = new Biff();
        g.record(0x0809, bof(0x0005));
        g.record(0x0042, data().u16(1200).bytes());
        g.record(0x041E, data().u16(164).unicode("dd/mm/yyyy").bytes());
        g.record(0x00E0, xf(0));
        g.record(0x00E0, xf(164));
        g.record(0x00E0, xf(14));
        g.record(0x0022, data().u16(0).bytes());
        while (padding > 0) {
            int n = Math.min(8000, padding);
            g.record(0x00EF, new byte[n]);
            padding -= n;
        }
        // BOUNDSHEET: offset preenchido depois.
        int boundsheetPos = g.size();
        byte[] name = data().u8(7).u8(0).chars("Extrato", false).bytes();
        g.record(0x0085, concat(data().i32(0).u8(0).u8(0).bytes(), name));
        for (int i = 0; i < sstParts.size(); i++) {
            g.record(i == 0 ? 0x00FC : 0x003C, sstParts.get(i));
        }
        g.record(0x000A, new byte[0]);
        byte[] globals = g.bytes();
        int sheetOffset = globals.length;
        // offset do BOF da aba no BOUNDSHEET (dados começam 4 bytes depois do cabeçalho do registro)
        ByteBuffer.wrap(globals).order(ByteOrder.LITTLE_ENDIAN).putInt(boundsheetPos + 4, sheetOffset);

        Biff sh = new Biff();
        sh.record(0x0809, bof(0x0010));
        byte[] c = cells.bytes();
        sh.out.write(c, 0, c.length);
        sh.record(0x000A, new byte[0]);
        return concat(globals, sh.bytes());
    }

    /** SST num registro só. */
    static List<byte[]> sst(String... strings) {
        Data d = data().i32(strings.length).i32(strings.length);
        for (String s : strings) {
            d.unicode(s);
        }
        List<byte[]> parts = new ArrayList<>();
        parts.add(d.bytes());
        return parts;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static void le16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    /** Contêiner OLE2 (versão 3, setores de 512 bytes) com um único fluxo "Workbook". */
    static byte[] ole2(byte[] stream) {
        final int sec = 512;
        boolean mini = stream.length < 4096;
        int[] fat = new int[128];
        java.util.Arrays.fill(fat, -1);
        fat[0] = -3; // setor da FAT
        fat[1] = -2; // diretório
        int next = 2;
        int miniFatSector = -2;
        int[] miniFat = null;
        int rootStart = -2;
        int rootSize = 0;
        int streamStart;
        byte[] payload;
        if (mini) {
            miniFatSector = next;
            fat[next++] = -2;
            int nm = (stream.length + 63) / 64;
            miniFat = new int[128];
            java.util.Arrays.fill(miniFat, -1);
            for (int i = 0; i < nm; i++) {
                miniFat[i] = i + 1 < nm ? i + 1 : -2;
            }
            rootSize = nm * 64;
            payload = new byte[rootSize];
            System.arraycopy(stream, 0, payload, 0, stream.length);
            rootStart = next;
            streamStart = 0;
        } else {
            payload = stream;
            streamStart = next;
        }
        int n = (payload.length + sec - 1) / sec;
        int first = next;
        for (int i = 0; i < n; i++) {
            fat[first + i] = i + 1 < n ? first + i + 1 : -2;
        }
        int totalSectors = first + n;

        ByteBuffer b = ByteBuffer.allocate((totalSectors + 1) * sec).order(ByteOrder.LITTLE_ENDIAN);
        // Cabeçalho
        b.put(new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});
        b.position(0x18);
        b.putShort((short) 0x3E).putShort((short) 3).putShort((short) 0xFFFE).putShort((short) 9).putShort((short) 6);
        b.position(0x2C);
        b.putInt(1).putInt(1).putInt(0).putInt(4096).putInt(miniFatSector).putInt(mini ? 1 : 0).putInt(-2).putInt(0);
        b.putInt(0); // DIFAT[0] = setor 0
        for (int i = 1; i < 109; i++) {
            b.putInt(-1);
        }
        // FAT (setor 0)
        b.position(sec);
        for (int v : fat) {
            b.putInt(v);
        }
        // Diretório (setor 1)
        b.position(2 * sec);
        dirEntry(b, "Root Entry", 5, 1, rootStart, rootSize);
        dirEntry(b, "Workbook", 2, -1, mini ? 0 : streamStart, stream.length);
        dirEntry(b, "", 0, -1, 0, 0);
        dirEntry(b, "", 0, -1, 0, 0);
        if (mini) {
            b.position((miniFatSector + 1) * sec);
            for (int v : miniFat) {
                b.putInt(v);
            }
        }
        b.position((first + 1) * sec);
        b.put(payload);
        return b.array();
    }

    private static void dirEntry(ByteBuffer b, String name, int type, int child, int start, int size) {
        int base = b.position();
        byte[] n = name.getBytes(StandardCharsets.UTF_16LE);
        b.put(n);
        b.position(base + 0x40);
        b.putShort((short) (name.isEmpty() ? 0 : n.length + 2));
        b.put((byte) type).put((byte) 1);
        b.putInt(-1).putInt(-1).putInt(child);
        b.position(base + 0x74);
        b.putInt(start).putInt(size).putInt(0);
        b.position(base + 128);
    }
}
