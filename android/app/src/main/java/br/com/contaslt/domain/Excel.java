package br.com.contaslt.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Leitor de planilhas (.xlsx/.xls), porte de frontend/src/domain/excel.ts. Procura uma linha de cabeçalho com colunas
 * de data, descrição e valor; sem cabeçalho, assume a ordem data | descrição | valor. Colunas opcionais: tipo e
 * categoria.
 *
 * <p>O PWA usa o SheetJS; aqui a leitura do arquivo é própria ({@link XlsxReader} para OOXML, {@link XlsReader} para o
 * BIFF8 dentro de OLE2), produzindo o mesmo modelo de célula do SheetJS (tipo s/n/b/e, valor, formato e texto
 * formatado) só da primeira aba. As regras de cabeçalho e de linha são as mesmas do excel.ts.
 */
public final class Excel {

    private static final Pattern JAVA_SPACES = Pattern.compile("[ \\t\\n\\x0B\\f\\r]+");

    private Excel() {
    }

    // ---------- Modelo de célula (como o CellObject do SheetJS) ----------

    /** Célula lida. {@code t}: 's' texto, 'n' número, 'b' booleano, 'e' erro. */
    static final class Cell {
        final char t;
        final double num;
        final String str;
        /** Código do formato numérico (só números), ou null. */
        final String format;

        private Cell(char t, double num, String str, String format) {
            this.t = t;
            this.num = num;
            this.str = str;
            this.format = format;
        }

        static Cell string(String s) {
            return new Cell('s', 0, s == null ? "" : s, null);
        }

        static Cell number(double v, String format) {
            return new Cell('n', v, null, format);
        }

        static Cell bool(boolean b) {
            return new Cell('b', b ? 1 : 0, b ? "TRUE" : "FALSE", null);
        }

        static Cell error(String text) {
            return new Cell('e', 0, text, null);
        }

        /** Texto formatado ({@code w} do SheetJS). */
        String formatted() {
            if (t != 'n') {
                return str;
            }
            return formatNumber(num, format);
        }
    }

    /** Primeira aba: células por posição e o intervalo usado ({@code !ref}). */
    static final class Sheet {
        final Map<Long, Cell> cells = new HashMap<>();
        int r0 = Integer.MAX_VALUE;
        int c0 = Integer.MAX_VALUE;
        int r1 = -1;
        int c1 = -1;
        boolean date1904;

        void put(int r, int c, Cell cell) {
            if (r < 0 || c < 0 || cell == null) {
                return;
            }
            cells.put(key(r, c), cell);
            extend(r, c, r, c);
        }

        /** Junta um intervalo declarado (dimension) ao intervalo usado. */
        void extend(int ra, int ca, int rb, int cb) {
            if (ra < 0 || ca < 0 || rb < ra || cb < ca) {
                return;
            }
            r0 = Math.min(r0, ra);
            c0 = Math.min(c0, ca);
            r1 = Math.max(r1, rb);
            c1 = Math.max(c1, cb);
        }

        boolean hasRange() {
            return r1 >= 0;
        }

        Cell get(int r, int c) {
            return c < 0 ? null : cells.get(key(r, c));
        }

        private static long key(int r, int c) {
            return ((long) r << 20) | c;
        }
    }

    // ---------- Leitura ----------

    private static final class Columns {
        final int date;
        final int description;
        final int amount;
        final int type;
        final int category;

        Columns(int date, int description, int amount, int type, int category) {
            this.date = date;
            this.description = description;
            this.amount = amount;
            this.type = type;
            this.category = category;
        }
    }

    public static List<ParsedRow> parseExcel(byte[] bytes) {
        Sheet sheet;
        try {
            // Como o WorkbookFactory do POI: só aceita OOXML (zip) ou OLE2 (.xls binário).
            if (isZip(bytes)) {
                sheet = XlsxReader.read(bytes);
            } else if (isOle2(bytes)) {
                sheet = XlsReader.read(bytes);
            } else {
                throw new IllegalArgumentException("o arquivo não é uma planilha do Excel (.xlsx ou .xls)");
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            throw new ParseException("Não foi possível ler a planilha: " + (msg == null ? e.toString() : msg));
        }
        List<ParsedRow> rows = new ArrayList<>();
        if (sheet.hasRange()) {
            int first = sheet.r0;
            int lastRow = sheet.r1;
            int headerRow = -1;
            Columns cols = null;
            int last = Math.min(lastRow, first + 30);
            for (int r = first; r <= last; r++) {
                Columns c = detectHeader(sheet, r, sheet.c0, sheet.c1);
                if (c != null) {
                    headerRow = r;
                    cols = c;
                    break;
                }
            }
            if (cols == null) {
                cols = new Columns(0, 1, 2, -1, -1);
            }
            for (int r = Math.max(headerRow + 1, first); r <= lastRow; r++) {
                ParsedRow row = parseRow(sheet, r, cols, sheet.date1904);
                if (row != null) {
                    rows.add(row);
                }
            }
        }
        if (rows.isEmpty()) {
            throw new ParseException(
                "Nenhuma linha com data e valor foi encontrada na planilha. Use colunas Data, Descrição e Valor.");
        }
        return rows;
    }

    static boolean isZip(byte[] b) {
        return b.length > 4 && b[0] == 0x50 && b[1] == 0x4b;
    }

    static boolean isOle2(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0xd0 && (b[1] & 0xFF) == 0xcf && (b[2] & 0xFF) == 0x11
            && (b[3] & 0xFF) == 0xe0;
    }

    private static Columns detectHeader(Sheet sheet, int r, int c0, int c1) {
        int date = -1;
        int desc = -1;
        int amount = -1;
        int type = -1;
        int category = -1;
        for (int i = c0; i <= c1; i++) {
            Cell c = sheet.get(r, i);
            if (c == null || c.t != 's') {
                continue;
            }
            String h = Text.normalize(text(c));
            if (date < 0 && (h.startsWith("data") || h.equals("date") || h.startsWith("dt ") || h.contains("vencimento"))) {
                date = i;
            } else if (desc < 0 && (h.contains("descri") || h.contains("historico") || h.contains("memo")
                || h.contains("lancamento") || h.contains("estabelecimento") || h.contains("titulo")
                || h.contains("description"))) {
                desc = i;
            } else if (amount < 0 && (h.contains("valor") || h.contains("amount") || h.contains("value")
                || h.contains("montante"))) {
                amount = i;
            } else if (type < 0 && (h.equals("tipo") || h.equals("type") || h.contains("natureza")
                || h.contains("credito debito"))) {
                type = i;
            } else if (category < 0 && (h.contains("categoria") || h.contains("category"))) {
                category = i;
            }
        }
        if (date >= 0 && amount >= 0) {
            if (desc < 0) {
                desc = findFreeColumn(date, amount);
            }
            return new Columns(date, desc, amount, type, category);
        }
        return null;
    }

    private static int findFreeColumn(int a, int b) {
        for (int i = 0; i < 10; i++) {
            if (i != a && i != b) {
                return i;
            }
        }
        return 1;
    }

    private static ParsedRow parseRow(Sheet sheet, int r, Columns cols, boolean date1904) {
        String date = readDate(sheet.get(r, cols.date), date1904);
        Long amount = readAmount(sheet.get(r, cols.amount));
        if (date == null || amount == null || amount == 0) {
            return null;
        }
        String description = text(sheet.get(r, cols.description));
        if (Text.trim(description).isEmpty()) {
            description = "Lançamento";
        }
        String type = amount < 0 ? "EXPENSE" : "INCOME";
        if (cols.type >= 0) {
            String t = Text.normalize(text(sheet.get(r, cols.type)));
            if (t.startsWith("d") || t.contains("saida") || t.contains("despesa") || t.contains("pagamento")) {
                type = "EXPENSE";
            } else if (t.startsWith("c") || t.contains("entrada") || t.contains("receita") || t.contains("recebimento")) {
                type = "INCOME";
            }
        }
        String categoryHint = cols.category >= 0 ? text(sheet.get(r, cols.category)) : null;
        return new ParsedRow(
            date,
            Text.truncate(JAVA_SPACES.matcher(description).replaceAll(" "), 140),
            Math.abs(amount),
            type,
            null,
            categoryHint == null || Text.trim(categoryHint).isEmpty() ? null : Text.trim(categoryHint));
    }

    /** Texto formatado da célula, como o DataFormatter do POI. */
    static String text(Cell c) {
        if (c == null) {
            return "";
        }
        String s = c.formatted();
        return s == null ? "" : Text.trim(s);
    }

    // ---------- Datas ----------

    private interface DateFormat {
        String parse(String s);
    }

    private static DateFormat fmt(String re, int yi, int mi, int di, int base) {
        Pattern p = Pattern.compile(re);
        return s -> {
            java.util.regex.Matcher m = p.matcher(s);
            if (!m.matches()) {
                return null;
            }
            try {
                return Ofx.smartDate(base + Integer.parseInt(m.group(yi)), Integer.parseInt(m.group(mi)),
                    Integer.parseInt(m.group(di)));
            } catch (NumberFormatException e) {
                return null;
            }
        };
    }

    /** Formatos aceitos quando a data vem como texto (na ordem do ExcelParser). */
    private static final DateFormat[] DATE_FORMATS = {
        fmt("^(\\d{2})/(\\d{2})/(\\d{4})$", 3, 2, 1, 0),        // dd/MM/yyyy
        fmt("^(\\d+)/(\\d+)/(\\d{4})$", 3, 2, 1, 0),            // d/M/yyyy
        fmt("^(\\d{2})/(\\d{2})/(\\d{2})$", 3, 2, 1, 2000),     // dd/MM/yy
        fmt("^(\\d{4})-(\\d{2})-(\\d{2})$", 1, 2, 3, 0),        // yyyy-MM-dd
        fmt("^(\\d{2})-(\\d{2})-(\\d{4})$", 3, 2, 1, 0),        // dd-MM-yyyy
        fmt("^(\\d{2})\\.(\\d{2})\\.(\\d{4})$", 3, 2, 1, 0),    // dd.MM.yyyy
    };

    static String readDate(Cell c, boolean date1904) {
        if (c == null) {
            return null;
        }
        if (c.t == 'n' && c.format != null && isDateFormat(c.format)) {
            int[] d = parseDateCode(c.num, date1904);
            if (d != null) {
                return Ofx.smartDate(d[0], d[1], d[2]);
            }
        }
        String s = text(c);
        if (s.length() > 10) {
            s = s.substring(0, 10);
        }
        for (DateFormat f : DATE_FORMATS) {
            String d = f.parse(s);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    /** Número de série do Excel → {ano, mês, dia}, como SSF.parse_date_code (inclui o 29/02/1900 fictício). */
    static int[] parseDateCode(double v, boolean date1904) {
        if (v > 2958465 || v < 0 || Double.isNaN(v)) {
            return null;
        }
        long date = (long) v;
        long time = (long) Math.floor(86400 * (v - date));
        double u = 86400 * (v - date) - time;
        if (Math.abs(u) < 1e-6) {
            u = 0;
        }
        if (date1904) {
            date += 1462;
        }
        if (u > 0.9999) {
            if (++time == 86400) {
                date++;
            }
        }
        if (date == 60) {
            return new int[] {1900, 2, 29};
        }
        if (date == 0) {
            return new int[] {1900, 1, 0};
        }
        if (date > 60) {
            date--;
        }
        LocalDate d = LocalDate.of(1900, 1, 1).plusDays(date - 1);
        return new int[] {d.getYear(), d.getMonthValue(), d.getDayOfMonth()};
    }

    /** Número de série de uma data (sistema 1900), para células {@code t="d"} do OOXML. */
    static double serialOf(LocalDate d, boolean date1904) {
        long days = d.toEpochDay() - LocalDate.of(1899, 12, 30).toEpochDay();
        if (date1904) {
            days -= 1462;
        }
        return days;
    }

    /** Formato numérico é de data/hora? Porte de SSF.is_date (fmt_is_date). */
    static boolean isDateFormat(String fmt) {
        int i = 0;
        int n = fmt.length();
        while (i < n) {
            char c = fmt.charAt(i);
            switch (c) {
                case 'G':
                    if (fmt.regionMatches(true, i, "General", 0, 7)) {
                        i += 6;
                    }
                    i++;
                    break;
                case '"':
                    i++;
                    while (i < n && fmt.charAt(i) != '"') {
                        i++;
                    }
                    i++;
                    break;
                case '\\':
                case '_':
                    i += 2;
                    break;
                case '@':
                    i++;
                    break;
                case 'B': case 'b':
                case 'M': case 'D': case 'Y': case 'H': case 'S': case 'E':
                case 'm': case 'd': case 'y': case 'h': case 's': case 'e': case 'g':
                    return true;
                case 'A':
                case 'a': {
                    String rest = fmt.substring(i).toUpperCase(Locale.ROOT);
                    if (rest.startsWith("A/P") || rest.startsWith("AM/PM")) {
                        return true;
                    }
                    i++;
                    break;
                }
                case '[': {
                    int end = fmt.indexOf(']', i);
                    String o = end < 0 ? fmt.substring(i) : fmt.substring(i, end + 1);
                    i = end < 0 ? n : end + 1;
                    if (o.matches("\\[[HhMmSs]*\\]")) {
                        return true;
                    }
                    break;
                }
                case '.':
                case '0':
                case '#':
                    i++;
                    while (i < n && "0#?.,E+-%".indexOf(fmt.charAt(i)) > -1) {
                        i++;
                    }
                    break;
                case '?':
                    while (i < n && fmt.charAt(i) == '?') {
                        i++;
                    }
                    break;
                case '*':
                    i++;
                    if (i < n && (fmt.charAt(i) == ' ' || fmt.charAt(i) == '*')) {
                        i++;
                    }
                    break;
                default:
                    i++;
                    break;
            }
        }
        return false;
    }

    /** Formatos embutidos do Excel (numFmtId) que importam aqui. */
    static String builtinFormat(int id) {
        switch (id) {
            case 0: return "General";
            case 1: return "0";
            case 2: return "0.00";
            case 3: return "#,##0";
            case 4: return "#,##0.00";
            case 9: return "0%";
            case 10: return "0.00%";
            case 11: return "0.00E+00";
            case 14: return "m/d/yy";
            case 15: return "d-mmm-yy";
            case 16: return "d-mmm";
            case 17: return "mmm-yy";
            case 18: return "h:mm AM/PM";
            case 19: return "h:mm:ss AM/PM";
            case 20: return "h:mm";
            case 21: return "h:mm:ss";
            case 22: return "m/d/yy h:mm";
            case 37: return "#,##0 ;(#,##0)";
            case 38: return "#,##0 ;[Red](#,##0)";
            case 39: return "#,##0.00;(#,##0.00)";
            case 40: return "#,##0.00;[Red](#,##0.00)";
            case 45: return "mm:ss";
            case 46: return "[h]:mm:ss";
            case 47: return "mmss.0";
            case 48: return "##0.0E+0";
            case 49: return "@";
            default:
                // Datas dos formatos regionais asiáticos (27–36, 50–58).
                if ((id >= 27 && id <= 36) || (id >= 50 && id <= 58)) {
                    return "m/d/yy";
                }
                return "General";
        }
    }

    // ---------- Valores ----------

    /** Valor da célula em centavos (com sinal). Número: arredondado a 2 casas (HALF_UP); texto: formato brasileiro. */
    static Long readAmount(Cell c) {
        if (c == null) {
            return null;
        }
        if (c.t == 'n') {
            return numberToCents(c.num);
        }
        String s = Text.trim(text(c).replace("R$", "").replace(" ", ""));
        if (s.isEmpty()) {
            return null;
        }
        boolean negative = s.startsWith("(") && s.endsWith(")");
        s = s.replace("(", "").replace(")", "");
        Long value = Ofx.parseAmount(s);
        if (value == null) {
            return null;
        }
        return negative ? -value : value;
    }

    /** Como BigDecimal.valueOf(double).setScale(2, HALF_UP): arredonda sobre a representação decimal do número. */
    private static Long numberToCents(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return null;
        }
        return Ofx.decimalToCents(Double.toString(v));
    }

    /**
     * Texto de um número como o SheetJS mostraria ({@code w}): formato geral (até 11 dígitos significativos) ou
     * casas decimais fixas, com separador de milhar e percentual quando o formato pede.
     */
    static String formatNumber(double v, String format) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return Double.toString(v);
        }
        String f = format == null ? "General" : format;
        int semi = f.indexOf(';');
        String section = semi >= 0 ? f.substring(0, semi) : f;
        String plain = section.replaceAll("\"[^\"]*\"|\\[[^\\]]*\\]|\\\\.|_.|\\*.", "");
        if (f.equalsIgnoreCase("General") || isDateFormat(f) || !plain.matches(".*[0#?].*") || plain.contains("E")) {
            return general(v);
        }
        boolean percent = plain.contains("%");
        double value = percent ? v * 100 : v;
        int dot = plain.indexOf('.');
        int decimals = 0;
        if (dot >= 0) {
            for (int i = dot + 1; i < plain.length() && "0#?".indexOf(plain.charAt(i)) >= 0; i++) {
                decimals++;
            }
        }
        BigDecimal bd = new BigDecimal(Double.toString(Math.abs(value))).setScale(decimals, java.math.RoundingMode.HALF_UP);
        String digits = bd.toPlainString();
        if (plain.contains(",")) {
            int p = digits.indexOf('.');
            String intPart = p >= 0 ? digits.substring(0, p) : digits;
            String frac = p >= 0 ? digits.substring(p) : "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < intPart.length(); i++) {
                if (i > 0 && (intPart.length() - i) % 3 == 0) {
                    sb.append(',');
                }
                sb.append(intPart.charAt(i));
            }
            digits = sb + frac;
        }
        boolean negative = value < 0 && bd.signum() != 0;
        return (negative ? "-" : "") + digits + (percent ? "%" : "");
    }

    private static String general(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e11) {
            return Long.toString((long) v);
        }
        double a = Math.abs(v);
        if (a >= 1e11 || (a > 0 && a < 1e-4)) {
            return Double.toString(v);
        }
        BigDecimal bd = new BigDecimal(v).round(new MathContext(11)).stripTrailingZeros();
        return bd.toPlainString();
    }
}
