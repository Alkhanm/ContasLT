package br.com.contaslt.imports;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import br.com.contaslt.common.BusinessException;
import br.com.contaslt.common.TextNormalizer;
import br.com.contaslt.common.TransactionType;

/**
 * Leitor de extratos OFX (SGML 1.x e XML 2.x). Trabalha por expressões regulares sobre os blocos
 * STMTTRN, o que tolera os arquivos "quase válidos" que os bancos brasileiros costumam gerar.
 */
@Component
public class OfxParser {

    private static final Pattern TRANSACTION = Pattern.compile("<STMTTRN>(.*?)(</STMTTRN>|(?=<STMTTRN>)|$)",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter OFX_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    public List<ParsedRow> parse(byte[] bytes) {
        String text = decode(bytes);
        if (!text.toUpperCase().contains("<OFX")) {
            throw new BusinessException("O arquivo não parece ser um extrato OFX válido");
        }
        List<ParsedRow> rows = new ArrayList<>();
        Matcher m = TRANSACTION.matcher(text);
        while (m.find()) {
            String block = m.group(1);
            String amountText = tag(block, "TRNAMT");
            String dateText = tag(block, "DTPOSTED");
            if (amountText == null || dateText == null || dateText.length() < 8) {
                continue;
            }
            BigDecimal amount = parseAmount(amountText);
            if (amount == null || amount.signum() == 0) {
                continue;
            }
            LocalDate date = LocalDate.parse(dateText.substring(0, 8), OFX_DATE);
            String memo = tag(block, "MEMO");
            String name = tag(block, "NAME");
            String description = memo != null && !memo.isBlank() ? memo : name;
            if (description == null || description.isBlank()) {
                description = tag(block, "TRNTYPE") != null ? tag(block, "TRNTYPE") : "Lançamento";
            }
            TransactionType type = amount.signum() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME;
            String fitId = tag(block, "FITID");
            rows.add(new ParsedRow(date, TextNormalizer.truncate(description.replaceAll("\\s+", " "), 140),
                amount.abs(), type, fitId == null || fitId.isBlank() ? null : TextNormalizer.truncate(fitId, 120), null));
        }
        if (rows.isEmpty()) {
            throw new BusinessException("Nenhum lançamento encontrado no arquivo OFX");
        }
        return rows;
    }

    static BigDecimal parseAmount(String raw) {
        String s = raw.trim().replace(" ", "").replace("−", "-");
        if (s.contains(",") && s.contains(".")) {
            s = s.lastIndexOf(',') > s.lastIndexOf('.') ? s.replace(".", "").replace(',', '.') : s.replace(",", "");
        } else if (s.contains(",")) {
            s = s.replace(',', '.');
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String tag(String block, String name) {
        Matcher m = Pattern.compile("<" + name + ">\\s*([^<\\r\\n]*)", Pattern.CASE_INSENSITIVE).matcher(block);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String decode(byte[] bytes) {
        String header = new String(bytes, 0, Math.min(bytes.length, 600), StandardCharsets.ISO_8859_1).toUpperCase();
        if (header.contains("CHARSET:1252") || header.contains("ENCODING:USASCII") || header.contains("ENCODING=\"ISO-8859-1\"")
                || header.contains("ENCODING=\"WINDOWS-1252\"")) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }
}
