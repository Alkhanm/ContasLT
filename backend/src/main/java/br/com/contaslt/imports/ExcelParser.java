package br.com.contaslt.imports;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import br.com.contaslt.common.BusinessException;
import br.com.contaslt.common.TextNormalizer;
import br.com.contaslt.common.TransactionType;

/**
 * Leitor de planilhas (.xlsx/.xls). Procura uma linha de cabeçalho com colunas de data, descrição e valor;
 * sem cabeçalho, assume a ordem data | descrição | valor. Colunas opcionais: tipo e categoria.
 */
@Component
public class ExcelParser {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
        DateTimeFormatter.ofPattern("dd/MM/yyyy"), DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yy"), DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy"), DateTimeFormatter.ofPattern("dd.MM.yyyy"));

    private record Columns(int date, int description, int amount, int type, int category) {
    }

    public List<ParsedRow> parse(byte[] bytes) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.forLanguageTag("pt-BR"));
            int headerRow = -1;
            Columns cols = null;
            int last = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 30);
            for (int r = sheet.getFirstRowNum(); r <= last; r++) {
                Columns c = detectHeader(sheet.getRow(r), fmt);
                if (c != null) {
                    headerRow = r;
                    cols = c;
                    break;
                }
            }
            if (cols == null) {
                cols = new Columns(0, 1, 2, -1, -1);
            }
            List<ParsedRow> rows = new ArrayList<>();
            for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                ParsedRow row = parseRow(sheet.getRow(r), cols, fmt);
                if (row != null) {
                    rows.add(row);
                }
            }
            if (rows.isEmpty()) {
                throw new BusinessException("Nenhuma linha com data e valor foi encontrada na planilha. "
                    + "Use colunas Data, Descrição e Valor.");
            }
            return rows;
        } catch (IOException | IllegalArgumentException e) {
            throw new BusinessException("Não foi possível ler a planilha: " + e.getMessage());
        }
    }

    private static Columns detectHeader(Row row, DataFormatter fmt) {
        if (row == null) {
            return null;
        }
        int date = -1;
        int desc = -1;
        int amount = -1;
        int type = -1;
        int category = -1;
        for (Cell cell : row) {
            if (cell.getCellType() != CellType.STRING) {
                continue;
            }
            String h = TextNormalizer.normalize(fmt.formatCellValue(cell));
            int i = cell.getColumnIndex();
            if (date < 0 && (h.startsWith("data") || h.equals("date") || h.startsWith("dt ") || h.contains("vencimento"))) {
                date = i;
            } else if (desc < 0 && (h.contains("descri") || h.contains("historico") || h.contains("memo")
                    || h.contains("lancamento") || h.contains("estabelecimento") || h.contains("titulo") || h.contains("description"))) {
                desc = i;
            } else if (amount < 0 && (h.contains("valor") || h.contains("amount") || h.contains("value") || h.contains("montante"))) {
                amount = i;
            } else if (type < 0 && (h.equals("tipo") || h.equals("type") || h.contains("natureza") || h.contains("credito debito"))) {
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

    private static ParsedRow parseRow(Row row, Columns cols, DataFormatter fmt) {
        if (row == null) {
            return null;
        }
        LocalDate date = readDate(row.getCell(cols.date()), fmt);
        BigDecimal amount = readAmount(row.getCell(cols.amount()), fmt);
        if (date == null || amount == null || amount.signum() == 0) {
            return null;
        }
        String description = text(row.getCell(cols.description()), fmt);
        if (description.isBlank()) {
            description = "Lançamento";
        }
        TransactionType type = amount.signum() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME;
        if (cols.type() >= 0) {
            String t = TextNormalizer.normalize(text(row.getCell(cols.type()), fmt));
            if (t.startsWith("d") || t.contains("saida") || t.contains("despesa") || t.contains("pagamento")) {
                type = TransactionType.EXPENSE;
            } else if (t.startsWith("c") || t.contains("entrada") || t.contains("receita") || t.contains("recebimento")) {
                type = TransactionType.INCOME;
            }
        }
        String categoryHint = cols.category() >= 0 ? text(row.getCell(cols.category()), fmt) : null;
        return new ParsedRow(date, TextNormalizer.truncate(description.replaceAll("\\s+", " "), 140), amount.abs(), type,
            null, categoryHint == null || categoryHint.isBlank() ? null : categoryHint.trim());
    }

    private static String text(Cell cell, DataFormatter fmt) {
        return cell == null ? "" : fmt.formatCellValue(cell).trim();
    }

    static LocalDate readDate(Cell cell, DataFormatter fmt) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC || cell.getCellType() == CellType.FORMULA) {
            try {
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toLocalDate();
                }
            } catch (IllegalStateException ignored) {
                // célula de fórmula não numérica; tenta como texto abaixo
            }
        }
        String s = text(cell, fmt);
        if (s.length() > 10) {
            s = s.substring(0, 10);
        }
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {
                // tenta o próximo formato
            }
        }
        return null;
    }

    static BigDecimal readAmount(Cell cell, DataFormatter fmt) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, java.math.RoundingMode.HALF_UP);
        }
        if (cell.getCellType() == CellType.FORMULA) {
            try {
                return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, java.math.RoundingMode.HALF_UP);
            } catch (IllegalStateException ignored) {
                // cai para leitura textual
            }
        }
        String s = text(cell, fmt).replace("R$", "").replace(" ", "").trim();
        if (s.isEmpty()) {
            return null;
        }
        boolean negative = s.startsWith("(") && s.endsWith(")");
        s = s.replace("(", "").replace(")", "");
        BigDecimal value = OfxParser.parseAmount(s);
        if (value == null) {
            return null;
        }
        return negative ? value.negate() : value;
    }
}
