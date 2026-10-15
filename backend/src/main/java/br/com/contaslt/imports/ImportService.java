package br.com.contaslt.imports;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import br.com.contaslt.account.Account;
import br.com.contaslt.account.AccountService;
import br.com.contaslt.category.Category;
import br.com.contaslt.category.CategoryRepository;
import br.com.contaslt.category.CategoryService;
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.common.TextNormalizer;
import br.com.contaslt.imports.ImportDtos.ConfirmRow;
import br.com.contaslt.imports.ImportDtos.ImportBatchDto;
import br.com.contaslt.imports.ImportDtos.ImportConfirmRequest;
import br.com.contaslt.imports.ImportDtos.ImportPreview;
import br.com.contaslt.imports.ImportDtos.ImportResult;
import br.com.contaslt.imports.ImportDtos.PreviewRow;
import br.com.contaslt.transaction.Transaction;
import br.com.contaslt.transaction.TransactionRepository;
import br.com.contaslt.user.User;

@Service
public class ImportService {

    private final OfxParser ofxParser;
    private final ExcelParser excelParser;
    private final AccountService accounts;
    private final CategoryService categories;
    private final CategoryRepository categoryRepository;
    private final CategoryRuleRepository rules;
    private final TransactionRepository transactions;
    private final ImportBatchRepository batches;

    public ImportService(OfxParser ofxParser, ExcelParser excelParser, AccountService accounts,
            CategoryService categories, CategoryRepository categoryRepository, CategoryRuleRepository rules,
            TransactionRepository transactions, ImportBatchRepository batches) {
        this.ofxParser = ofxParser;
        this.excelParser = excelParser;
        this.accounts = accounts;
        this.categories = categories;
        this.categoryRepository = categoryRepository;
        this.rules = rules;
        this.transactions = transactions;
        this.batches = batches;
    }

    @Transactional(readOnly = true)
    public ImportPreview preview(User user, Long accountId, MultipartFile file) {
        Account account = accounts.require(user.getId(), accountId);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("Não foi possível ler o arquivo enviado");
        }
        String filename = file.getOriginalFilename() == null ? "arquivo" : file.getOriginalFilename();
        ImportSource source = detectSource(filename, bytes);
        List<ParsedRow> parsed = source == ImportSource.OFX ? ofxParser.parse(bytes) : excelParser.parse(bytes);
        parsed = parsed.stream().sorted(Comparator.comparing(ParsedRow::date).reversed()).toList();

        CategorySuggester suggester = new CategorySuggester(
            categoryRepository.findByUserIdOrderByTypeAscNameAsc(user.getId()),
            rules.findByUserIdOrderByKeywordAsc(user.getId()),
            transactions.findRecentCategorized(user.getId()));

        Set<String> externalIds = parsed.stream().map(ParsedRow::externalId).filter(id -> id != null)
            .collect(Collectors.toSet());
        Set<String> existingExternal = externalIds.isEmpty() ? Set.of()
            : transactions.findExistingExternalIds(account.getId(), externalIds);
        LocalDate min = parsed.stream().map(ParsedRow::date).min(Comparator.naturalOrder()).orElse(LocalDate.now());
        LocalDate max = parsed.stream().map(ParsedRow::date).max(Comparator.naturalOrder()).orElse(LocalDate.now());
        Set<String> existingSignatures = transactions.findByAccountAndPeriod(account.getId(), min, max).stream()
            .map(t -> signature(t.getDueDate(), t.getAmount(), t.getDescription()))
            .collect(Collectors.toSet());

        List<PreviewRow> rows = new ArrayList<>();
        int duplicates = 0;
        int index = 0;
        for (ParsedRow p : parsed) {
            boolean duplicate = (p.externalId() != null && existingExternal.contains(p.externalId()))
                || existingSignatures.contains(signature(p.date(), p.amount(), p.description()));
            if (duplicate) {
                duplicates++;
            }
            Optional<Category> suggestion = suggester.suggest(p.description(), p.type(), p.categoryHint());
            rows.add(new PreviewRow(index++, p.date(), p.description(), p.amount(), p.type(), p.externalId(), duplicate,
                suggestion.map(Category::getId).orElse(null), suggestion.map(Category::getName).orElse(null),
                suggestion.map(Category::getColor).orElse(null), suggestion.map(Category::getIcon).orElse(null)));
        }
        return new ImportPreview(source, filename, account.getId(), rows.size(), duplicates, rows);
    }

    @Transactional
    public ImportResult confirm(User user, ImportConfirmRequest req) {
        Account account = accounts.require(user.getId(), req.accountId());
        ImportBatch batch = new ImportBatch();
        batch.setUser(user);
        batch.setAccount(account);
        batch.setSource(req.source());
        batch.setFilename(TextNormalizer.truncate(req.filename(), 255));
        batch = batches.save(batch);

        Set<String> seenExternal = new HashSet<>();
        int imported = 0;
        int skipped = 0;
        for (ConfirmRow row : req.rows()) {
            String externalId = row.externalId() == null || row.externalId().isBlank() ? null : row.externalId().trim();
            if (externalId != null && (!seenExternal.add(externalId)
                    || transactions.existsByAccountIdAndExternalId(account.getId(), externalId))) {
                skipped++;
                continue;
            }
            Category category = categories.requireOrNull(user.getId(), row.categoryId());
            if (category != null && category.getType() != row.type()) {
                throw new BusinessException("Categoria \"" + category.getName() + "\" não é do tipo do lançamento \""
                    + row.description() + "\"");
            }
            Transaction t = new Transaction();
            t.setUser(user);
            t.setAccount(account);
            t.setCategory(category);
            t.setImportBatch(batch);
            t.setDescription(row.description().trim());
            t.setAmount(row.amount());
            t.setType(row.type());
            t.setDueDate(row.date());
            t.setPaid(row.paid() == null || row.paid());
            t.setExternalId(externalId);
            transactions.save(t);
            imported++;

            if (category != null && Boolean.TRUE.equals(row.learnRule())) {
                learn(user, category, row.description());
            }
        }
        batch.setImportedCount(imported);
        return new ImportResult(batch.getId(), imported, skipped);
    }

    @Transactional(readOnly = true)
    public List<ImportBatchDto> history(Long userId) {
        return batches.findTop50ByUserIdOrderByIdDesc(userId).stream()
            .map(b -> new ImportBatchDto(b.getId(), b.getSource(), b.getFilename(), b.getAccount().getId(),
                b.getAccount().getName(), b.getImportedCount(), b.getCreatedAt()))
            .toList();
    }

    private void learn(User user, Category category, String description) {
        String keyword = TextNormalizer.truncate(TextNormalizer.normalize(description), 120);
        if (keyword.length() < 3) {
            return;
        }
        CategoryRule rule = rules.findByUserIdAndKeyword(user.getId(), keyword).orElseGet(() -> {
            CategoryRule r = new CategoryRule();
            r.setUser(user);
            r.setKeyword(keyword);
            return r;
        });
        rule.setCategory(category);
        rules.save(rule);
    }

    static ImportSource detectSource(String filename, byte[] bytes) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ofx") || lower.endsWith(".qfx")) {
            return ImportSource.OFX;
        }
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".xlsm")) {
            return ImportSource.EXCEL;
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 400), java.nio.charset.StandardCharsets.ISO_8859_1)
            .toUpperCase(Locale.ROOT);
        if (head.contains("OFXHEADER") || head.contains("<OFX")) {
            return ImportSource.OFX;
        }
        if (bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K') {
            return ImportSource.EXCEL;
        }
        throw new BusinessException("Formato não suportado. Envie um arquivo .ofx, .xlsx ou .xls");
    }

    private static String signature(LocalDate date, java.math.BigDecimal amount, String description) {
        return date + "|" + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "|"
            + TextNormalizer.normalize(description);
    }
}
