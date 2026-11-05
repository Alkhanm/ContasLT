package br.com.contaslt.domain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Leitura da primeira aba de um .xlsx (OOXML): zip com XML. Lê textos compartilhados, textos em linha, números,
 * booleanos, erros e o formato numérico de cada célula (styles.xml), para reconhecer datas como o SheetJS.
 */
final class XlsxReader {

    /** Limite do conteúdo descompactado (proteção contra zip-bomba). */
    private static final long MAX_TOTAL = 256L * 1024 * 1024;
    private static final Pattern ESCAPE = Pattern.compile("_x([0-9A-Fa-f]{4})_");

    private XlsxReader() {
    }

    static Excel.Sheet read(byte[] bytes) throws Exception {
        Map<String, byte[]> files = unzip(bytes);
        String workbookPath = "xl/workbook.xml";
        byte[] rootRels = files.get("_rels/.rels");
        if (rootRels != null) {
            for (Element rel : children(parse(rootRels).getDocumentElement(), "Relationship")) {
                if (rel.getAttribute("Type").endsWith("/officeDocument")) {
                    workbookPath = resolve("", rel.getAttribute("Target"));
                }
            }
        }
        byte[] workbookXml = files.get(workbookPath);
        if (workbookXml == null) {
            throw new IllegalArgumentException("o arquivo não é uma planilha do Excel (.xlsx ou .xls)");
        }
        String base = workbookPath.contains("/") ? workbookPath.substring(0, workbookPath.lastIndexOf('/') + 1) : "";
        Element workbook = parse(workbookXml).getDocumentElement();
        boolean date1904 = false;
        for (Element pr : children(workbook, "workbookPr")) {
            String v = pr.getAttribute("date1904");
            date1904 = "1".equals(v) || "true".equalsIgnoreCase(v);
        }
        String firstRid = null;
        for (Element sheets : children(workbook, "sheets")) {
            for (Element sh : children(sheets, "sheet")) {
                firstRid = attrNs(sh, "id");
                break;
            }
        }
        if (firstRid == null) {
            throw new IllegalArgumentException("a planilha não tem abas");
        }
        Map<String, String> targets = new HashMap<>();
        String relsPath = base + "_rels/" + workbookPath.substring(base.length()) + ".rels";
        byte[] wbRels = files.get(relsPath);
        String sharedStringsPath = base + "sharedStrings.xml";
        String stylesPath = base + "styles.xml";
        if (wbRels != null) {
            for (Element rel : children(parse(wbRels).getDocumentElement(), "Relationship")) {
                String target = resolve(base, rel.getAttribute("Target"));
                targets.put(rel.getAttribute("Id"), target);
                String type = rel.getAttribute("Type");
                if (type.endsWith("/sharedStrings")) {
                    sharedStringsPath = target;
                } else if (type.endsWith("/styles")) {
                    stylesPath = target;
                }
            }
        }
        String sheetPath = targets.get(firstRid);
        if (sheetPath == null || !files.containsKey(sheetPath)) {
            sheetPath = base + "worksheets/sheet1.xml";
        }
        byte[] sheetXml = files.get(sheetPath);
        if (sheetXml == null) {
            throw new IllegalArgumentException("a planilha não tem abas");
        }

        List<String> shared = new ArrayList<>();
        byte[] sst = files.get(sharedStringsPath);
        if (sst != null) {
            for (Element si : children(parse(sst).getDocumentElement(), "si")) {
                shared.add(richText(si));
            }
        }
        List<String> xfFormats = readStyles(files.get(stylesPath));

        Excel.Sheet out = new Excel.Sheet();
        out.date1904 = date1904;
        Element ws = parse(sheetXml).getDocumentElement();
        for (Element dim : children(ws, "dimension")) {
            String ref = dim.getAttribute("ref");
            if (!ref.isEmpty()) {
                String[] parts = ref.split(":");
                int[] a = cellRef(parts[0]);
                int[] b = parts.length > 1 ? cellRef(parts[1]) : a;
                if (a != null && b != null) {
                    out.extend(a[0], a[1], b[0], b[1]);
                }
            }
        }
        for (Element data : children(ws, "sheetData")) {
            int rowIndex = -1;
            for (Element row : children(data, "row")) {
                String rAttr = row.getAttribute("r");
                rowIndex = rAttr.isEmpty() ? rowIndex + 1 : Integer.parseInt(rAttr.trim()) - 1;
                int col = -1;
                for (Element c : children(row, "c")) {
                    String ref = c.getAttribute("r");
                    int[] pos = ref.isEmpty() ? null : cellRef(ref);
                    int r = rowIndex;
                    if (pos != null) {
                        r = pos[0];
                        col = pos[1];
                    } else {
                        col++;
                    }
                    out.put(r, col, cell(c, shared, xfFormats, date1904));
                }
            }
        }
        return out;
    }

    private static Excel.Cell cell(Element c, List<String> shared, List<String> xfFormats, boolean date1904) {
        String t = c.getAttribute("t");
        String v = null;
        Element is = null;
        for (Node n = c.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String name = localName(n);
            if (name.equals("v")) {
                v = n.getTextContent();
            } else if (name.equals("is")) {
                is = (Element) n;
            }
        }
        switch (t) {
            case "s": {
                if (v == null) {
                    return null;
                }
                int idx = Integer.parseInt(v.trim());
                return Excel.Cell.string(idx >= 0 && idx < shared.size() ? shared.get(idx) : "");
            }
            case "inlineStr":
                return is != null ? Excel.Cell.string(richText(is)) : v != null ? Excel.Cell.string(unescape(v)) : null;
            case "str":
                return v == null ? null : Excel.Cell.string(unescape(v));
            case "b":
                return v == null ? null : Excel.Cell.bool(!v.trim().equals("0") && !v.trim().isEmpty());
            case "e":
                return v == null ? null : Excel.Cell.error(v);
            case "d": {
                if (v == null) {
                    return null;
                }
                try {
                    LocalDate d = LocalDate.parse(v.trim().substring(0, 10));
                    String f = format(c, xfFormats);
                    return Excel.Cell.number(Excel.serialOf(d, date1904), Excel.isDateFormat(f) ? f : "m/d/yy");
                } catch (RuntimeException e) {
                    return Excel.Cell.string(v);
                }
            }
            default: {
                if (v == null || v.trim().isEmpty()) {
                    return null;
                }
                double num;
                try {
                    num = Double.parseDouble(v.trim());
                } catch (NumberFormatException e) {
                    return Excel.Cell.string(v);
                }
                return Excel.Cell.number(num, format(c, xfFormats));
            }
        }
    }

    private static String format(Element c, List<String> xfFormats) {
        String s = c.getAttribute("s");
        if (s.isEmpty()) {
            return xfFormats.isEmpty() ? "General" : xfFormats.get(0);
        }
        int idx = Integer.parseInt(s.trim());
        return idx >= 0 && idx < xfFormats.size() ? xfFormats.get(idx) : "General";
    }

    /** Código de formato de cada estilo de célula (cellXfs), na ordem. */
    private static List<String> readStyles(byte[] xml) throws Exception {
        List<String> out = new ArrayList<>();
        if (xml == null) {
            return out;
        }
        Element root = parse(xml).getDocumentElement();
        Map<Integer, String> custom = new HashMap<>();
        for (Element fmts : children(root, "numFmts")) {
            for (Element f : children(fmts, "numFmt")) {
                try {
                    custom.put(Integer.parseInt(f.getAttribute("numFmtId").trim()), f.getAttribute("formatCode"));
                } catch (NumberFormatException ignored) {
                    // formato sem número: ignorado
                }
            }
        }
        for (Element xfs : children(root, "cellXfs")) {
            for (Element xf : children(xfs, "xf")) {
                int id = 0;
                try {
                    String a = xf.getAttribute("numFmtId");
                    id = a.isEmpty() ? 0 : Integer.parseInt(a.trim());
                } catch (NumberFormatException ignored) {
                    id = 0;
                }
                String code = custom.get(id);
                out.add(code != null ? code : Excel.builtinFormat(id));
            }
        }
        return out;
    }

    /** Texto de um {@code <si>}/{@code <is>}: os {@code <t>} (diretos ou em runs), sem a leitura fonética. */
    private static String richText(Element e) {
        StringBuilder sb = new StringBuilder();
        collectText(e, sb);
        return unescape(sb.toString());
    }

    private static void collectText(Node n, StringBuilder sb) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String name = localName(c);
            if (name.equals("t")) {
                sb.append(c.getTextContent());
            } else if (name.equals("r")) {
                collectText(c, sb);
            }
        }
    }

    /** Escapes {@code _xHHHH_} do OOXML. */
    private static String unescape(String s) {
        if (s.indexOf("_x") < 0) {
            return s;
        }
        java.util.regex.Matcher m = ESCAPE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                String.valueOf((char) Integer.parseInt(m.group(1), 16))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** "B12" → {linha 11, coluna 1}. */
    static int[] cellRef(String ref) {
        int i = 0;
        int col = 0;
        String s = ref.trim().replace("$", "");
        while (i < s.length() && Character.isLetter(s.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(s.charAt(i)) - 'A' + 1);
            i++;
        }
        if (i == 0 || i == s.length()) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(s.substring(i)) - 1, col - 1};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String resolve(String base, String target) {
        if (target.startsWith("/")) {
            return target.substring(1);
        }
        List<String> parts = new ArrayList<>();
        for (String p : (base + target).split("/")) {
            if (p.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
            } else if (!p.isEmpty() && !p.equals(".")) {
                parts.add(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    // ---------- Zip e XML ----------

    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        long total = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_TOTAL) {
                        throw new IOException("arquivo grande demais");
                    }
                    bos.write(buf, 0, n);
                }
                String name = e.getName().replace('\\', '/');
                out.put(name.startsWith("/") ? name.substring(1) : name, bos.toByteArray());
            }
        }
        if (out.isEmpty()) {
            throw new IOException("o arquivo não é uma planilha do Excel (.xlsx ou .xls)");
        }
        return out;
    }

    static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setExpandEntityReferences(false);
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // nem todo parser (Android) conhece o recurso
        }
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(xml));
    }

    static String localName(Node n) {
        String name = n.getLocalName();
        if (name == null) {
            name = n.getNodeName();
            int colon = name.indexOf(':');
            if (colon >= 0) {
                name = name.substring(colon + 1);
            }
        }
        return name;
    }

    /** Atributo sem olhar o prefixo (r:id). */
    private static String attrNs(Element e, String local) {
        org.w3c.dom.NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String name = a.getNodeName();
            if (name.endsWith(":" + local)) {
                return a.getNodeValue();
            }
        }
        return null;
    }

    static List<Element> children(Element parent, String local) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && localName(n).equals(local)) {
                out.add((Element) n);
            }
        }
        return out;
    }
}
