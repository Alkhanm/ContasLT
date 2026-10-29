package br.com.contaslt.imports;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
import br.com.contaslt.card.CardInvoiceDtos.CardInvoiceDto;
import br.com.contaslt.card.CardInvoiceService;
import br.com.contaslt.card.InvoicePeriod;
import br.com.contaslt.transfer.TransferService;
import br.com.contaslt.category.Category;
import br.com.contaslt.category.CategoryRepository;
import br.com.contaslt.category.CategoryService;
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.common.TransactionType;
import br.com.contaslt.common.NotFoundException;
import br.com.contaslt.common.TextNormalizer;
import br.com.contaslt.imports.ImportDtos.ConfirmRow;
import br.com.contaslt.imports.ImportDtos.ImportBatchDto;
import br.com.contaslt.imports.ImportDtos.ImportConfirmRequest;
import br.com.contaslt.imports.ImportDtos.ImportPreview;
import br.com.contaslt.imports.ImportDtos.ImportResult;
import br.com.contaslt.imports.ImportDtos.PreviewRow;
import br.com.contaslt.recurring.OccurrenceMatcher;
import br.com.contaslt.recurring.RecurringBillService;
import br.com.contaslt.recurring.RecurringBillService.ScoredOccurrence;
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
    private final RecurringBillService recurring;
    private final TransferService transfers;
    private final CardInvoiceService cardInvoices;

    public ImportService(OfxParser ofxParser, ExcelParser excelParser, AccountService accounts,
            CategoryService categories, CategoryRepository categoryRepository, CategoryRuleRepository rules,
            TransactionRepository transactions, ImportBatchRepository batches, RecurringBillService recurring,
            TransferService transfers, CardInvoiceService cardInvoices) {
        this.recurring = recurring;
        this.transfers = transfers;
        this.cardInvoices = cardInvoices;
        this.ofxParser = ofxParser;
        this.excelParser = excelParser;
        this.accounts = accounts;
        this.categories = categories;
        this.categoryRepository = categoryRepository;
        this.rules = rules;
        this.transactions = transactions;
        this.batches = batches;
    }

    @Transactional
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
            rules.findByUserIdOrderByKeywordAsc(user.getId()));

        Set<String> externalIds = parsed.stream().map(ParsedRow::externalId).filter(id -> id != null)
            .collect(Collectors.toSet());
        Set<String> existingExternal = externalIds.isEmpty() ? Set.of()
            : transactions.findExistingExternalIds(account.getId(), externalIds);
        LocalDate min = parsed.stream().map(ParsedRow::date).min(Comparator.naturalOrder()).orElse(LocalDate.now());
        LocalDate max = parsed.stream().map(ParsedRow::date).max(Comparator.naturalOrder()).orElse(LocalDate.now());
        Set<String> existingSignatures = transactions.findByAccountAndPeriod(account.getId(), min, max).stream()
            .map(t -> signature(t.getDueDate(), t.getAmount(), t.getDescription()))
            .collect(Collectors.toSet());

        Map<ParsedRow, Transaction> matches = matchExisting(user, account, parsed, min, max);
        List<Account> cards = account.isCreditCard() ? List.of() : accounts.listEntities(user.getId()).stream()
            .filter(a -> a.isCreditCard() && !a.isArchived()).toList();

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
            Transaction m = duplicate ? null : matches.get(p);
            Category mc = m == null ? null : m.getCategory();
            Account card = duplicate || m != null ? null : invoiceCardFor(p, cards);
            CardInvoiceDto inv = card == null ? null
                : cardInvoices.summarize(card, CardInvoiceService.inferInvoiceMonth(card, p.date()));
            rows.add(new PreviewRow(index++, p.date(), p.description(), p.amount(), p.type(), p.externalId(), duplicate,
                suggestion.map(Category::getId).orElse(null), suggestion.map(Category::getName).orElse(null),
                suggestion.map(Category::getColor).orElse(null), suggestion.map(Category::getIcon).orElse(null),
                m == null ? null : m.getId(), m == null ? null : m.getDescription(), m == null ? null : m.getDueDate(),
                m == null ? null : m.getAccount().getName(), m == null ? 0 : m.getItems().size(),
                m != null && m.getReceiptFile() != null,
                mc == null ? null : mc.getId(), mc == null ? null : mc.getName(),
                m != null && m.getRecurringBill() != null, m == null ? null : m.getAmount(),
                inv == null ? null : card.getId(), inv == null ? null : card.getName(), inv == null ? null : inv.month(),
                inv == null ? null : inv.dueDate(), inv == null ? null : inv.total(), inv == null ? null : inv.paid()));
        }
        return new ImportPreview(source, filename, account.getId(), rows.size(), duplicates, rows);
    }

    /** Janela de dias entre a data do extrato e a do lançamento manual para considerá-los a mesma compra. */
    static final int MATCH_DAYS = 3;

    private static final List<String> INVOICE_WORDS = List.of("fatura", "pgto cartao", "pagto cartao", "pagamento cartao",
        "pag cartao", "pagamento de cartao", "cartao de credito", "cartao credito");

    /**
     * Linha do extrato da conta que parece ser o pagamento da fatura de um cartão: despesa cuja descrição fala em
     * fatura/pagamento de cartão ou cita o nome de um cartão cadastrado. O cartão citado pelo nome tem preferência;
     * sem citação, vale o único cartão, ou o primeiro.
     */
    private static Account invoiceCardFor(ParsedRow row, List<Account> cards) {
        if (cards.isEmpty() || row.type() != TransactionType.EXPENSE) {
            return null;
        }
        String d = " " + TextNormalizer.normalize(row.description()).toLowerCase(Locale.ROOT) + " ";
        Account named = null;
        for (Account c : cards) {
            String name = TextNormalizer.normalize(c.getName()).toLowerCase(Locale.ROOT);
            for (String token : name.split(" ")) {
                if (token.length() >= 4 && !token.equals("cartao") && !token.equals("credito") && d.contains(token)) {
                    named = c;
                }
            }
        }
        boolean invoiceWord = INVOICE_WORDS.stream().anyMatch(d::contains);
        if (named != null && (invoiceWord || d.contains("pagamento") || d.contains("pgto") || d.contains("pagto"))) {
            return named;
        }
        return invoiceWord ? (named != null ? named : cards.get(0)) : null;
    }

    /**
     * Procura, para cada linha do extrato, um lançamento criado à mão (comprovante, lançamento manual) com o
     * mesmo valor e tipo, até {@link #MATCH_DAYS} dias de distância. Cada lançamento casa com no máximo uma linha;
     * em caso de empate vence a mesma conta, a data mais próxima e a descrição mais parecida.
     */
    private Map<ParsedRow, Transaction> matchExisting(User user, Account account, List<ParsedRow> parsed,
            LocalDate min, LocalDate max) {
        List<Transaction> candidates = new ArrayList<>(transactions.findImportCandidates(user.getId(),
            min.minusDays(MATCH_DAYS), max.plusDays(MATCH_DAYS)));
        if (account.isCreditCard()) {
            // Extrato do cartão: "pagamento recebido" casa com a perna do pagamento de fatura já registrado.
            candidates.addAll(transactions.findInvoicePaymentLegs(account.getId(), min.minusDays(MATCH_DAYS),
                max.plusDays(MATCH_DAYS)));
        }
        record Pair(ParsedRow row, Transaction tx, int score) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (ParsedRow row : parsed) {
            Set<String> rowTokens = tokens(row.description());
            for (Transaction tx : candidates) {
                if (tx.getType() != row.type() || tx.getAmount().compareTo(row.amount()) != 0) {
                    continue;
                }
                int days = (int) Math.abs(ChronoUnit.DAYS.between(tx.getDueDate(), row.date()));
                if (days > MATCH_DAYS) {
                    continue;
                }
                int score = 100 - days * 15
                    + (tx.getAccount().getId().equals(account.getId()) ? 30 : 0)
                    + (tx.getReceiptFile() != null || !tx.getItems().isEmpty() ? 10 : 0);
                Set<String> txTokens = tokens(tx.getDescription() + " " + (tx.getMerchant() == null ? "" : tx.getMerchant()));
                if (rowTokens.stream().anyMatch(txTokens::contains)) {
                    score += 25;
                }
                pairs.add(new Pair(row, tx, score));
            }
        }
        // Vencimentos pendentes de contas recorrentes: nome como chave, valor e data dentro da precisão da regra.
        Map<ParsedRow, ScoredOccurrence> occurrences = recurring.matchPending(user, parsed,
            p -> new OccurrenceMatcher.Candidate(p.description(), p.amount(), p.type(), p.date()), min, max);
        occurrences.forEach((row, so) -> pairs.add(new Pair(row, so.occurrence(),
            so.score() + (so.occurrence().getAccount().getId().equals(account.getId()) ? 30 : 0))));
        pairs.sort(Comparator.comparingInt(Pair::score).reversed());
        Map<ParsedRow, Transaction> result = new HashMap<>();
        Set<Long> usedTx = new HashSet<>();
        for (Pair pair : pairs) {
            if (result.containsKey(pair.row()) || usedTx.contains(pair.tx().getId())) {
                continue;
            }
            result.put(pair.row(), pair.tx());
            usedTx.add(pair.tx().getId());
        }
        return result;
    }

    private static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        for (String t : TextNormalizer.normalize(text).split(" ")) {
            if (t.length() >= 4) {
                out.add(t);
            }
        }
        return out;
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
        Set<Long> linkedIds = new HashSet<>();
        int imported = 0;
        int skipped = 0;
        int linked = 0;
        int invoicePayments = 0;
        Set<YearMonth> touchedInvoices = new HashSet<>();
        for (ConfirmRow row : req.rows()) {
            String externalId = row.externalId() == null || row.externalId().isBlank() ? null : row.externalId().trim();
            if (externalId != null && (!seenExternal.add(externalId)
                    || transactions.existsByAccountIdAndExternalId(account.getId(), externalId))) {
                skipped++;
                continue;
            }
            if (row.linkTransactionId() != null) {
                Transaction existing = transactions.findByIdAndUserId(row.linkTransactionId(), user.getId())
                    .orElseThrow(() -> new NotFoundException("Lançamento a vincular não encontrado"));
                if (existing.getImportBatch() != null || !linkedIds.add(existing.getId())) {
                    throw new BusinessException("O lançamento \"" + existing.getDescription()
                        + "\" já está vinculado a uma importação");
                }
                if (existing.getType() != row.type()) {
                    throw new BusinessException("O lançamento \"" + existing.getDescription()
                        + "\" não é do mesmo tipo da linha do extrato");
                }
                if (existing.getRecurringBill() != null) {
                    // Vencimento previsto vira o pagamento real: valor e data são os do extrato.
                    RecurringBillService.settle(existing, row.amount(), row.date());
                    if (existing.getCategory() == null && row.categoryId() != null) {
                        existing.setCategory(categories.requireOrNull(user.getId(), row.categoryId()));
                    }
                } else if (existing.getAmount().compareTo(row.amount()) != 0) {
                    throw new BusinessException("O lançamento \"" + existing.getDescription()
                        + "\" não tem o mesmo valor da linha do extrato");
                }
                // O lançamento manual (com itens, comprovante, categoria) passa a representar a linha do extrato.
                existing.setAccount(account);
                existing.setImportBatch(batch);
                existing.setExternalId(externalId);
                existing.setLinkedImport(true);
                existing.setPaid(true);
                linked++;
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

            if (row.invoiceCardId() != null) {
                if (row.type() != TransactionType.EXPENSE) {
                    throw new BusinessException("Só uma saída pode ser pagamento de fatura: \"" + row.description() + "\"");
                }
                Account card = cardInvoices.requireCard(user.getId(), row.invoiceCardId());
                YearMonth month = row.invoiceMonth() != null ? row.invoiceMonth()
                    : CardInvoiceService.inferInvoiceMonth(card, row.date());
                transfers.payInvoice(user, t, card, month);
                invoicePayments++;
            } else if (account.isCreditCard()) {
                touchedInvoices.add(InvoicePeriod.containing(account, row.date()).month());
            }

            if (category != null && Boolean.TRUE.equals(row.learnRule())) {
                learn(user, category, row.description());
            }
        }
        for (YearMonth month : touchedInvoices) {
            cardInvoices.refresh(user, account, month);
        }
        batch.setImportedCount(imported + linked);
        return new ImportResult(batch.getId(), imported, skipped, linked, invoicePayments);
    }

    @Transactional(readOnly = true)
    public List<ImportBatchDto> history(Long userId) {
        return batches.findTop50ByUserIdOrderByIdDesc(userId).stream()
            .map(b -> new ImportBatchDto(b.getId(), b.getSource(), b.getFilename(), b.getAccount().getId(),
                b.getAccount().getName(), b.getImportedCount(), b.getCreatedAt()))
            .toList();
    }

    /** Exclui o lote e todos os lançamentos criados por ele. Retorna quantos lançamentos foram removidos. */
    @Transactional
    public int delete(Long userId, Long batchId) {
        ImportBatch batch = batches.findByIdAndUserId(batchId, userId)
            .orElseThrow(() -> new NotFoundException("Importação não encontrada"));
        // Lançamentos que já existiam e só foram vinculados ao extrato continuam; apenas perdem o vínculo.
        transactions.detachLinkedFromImportBatch(batch.getId());
        int removed = transactions.deleteByImportBatchId(batch.getId());
        batches.delete(batch);
        return removed;
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
