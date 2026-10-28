package br.com.contaslt.receipt;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import br.com.contaslt.category.Category;
import br.com.contaslt.category.CategoryRepository;
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.common.TransactionType;
import br.com.contaslt.imports.CategoryRuleRepository;
import br.com.contaslt.imports.CategorySuggester;
import br.com.contaslt.receipt.ReceiptDtos.ReceiptScan;
import br.com.contaslt.receipt.ReceiptDtos.ReceiptScanResponse;
import br.com.contaslt.receipt.ReceiptDtos.ScannedItem;
import br.com.contaslt.recurring.OccurrenceMatcher;
import br.com.contaslt.recurring.RecurringBillService;
import br.com.contaslt.recurring.RecurringBillService.ScoredOccurrence;
import br.com.contaslt.transaction.Transaction;
import br.com.contaslt.transaction.TransactionRepository;
import br.com.contaslt.user.User;

@Service
public class ReceiptService {

    private final ReceiptScanner scanner;
    private final ReceiptStorage storage;
    private final CategoryRepository categories;
    private final CategoryRuleRepository rules;
    private final TransactionRepository transactions;
    private final RecurringBillService recurring;

    public ReceiptService(ReceiptScanner scanner, ReceiptStorage storage, CategoryRepository categories,
            RecurringBillService recurring,
            CategoryRuleRepository rules, TransactionRepository transactions) {
        this.scanner = scanner;
        this.storage = storage;
        this.categories = categories;
        this.rules = rules;
        this.transactions = transactions;
        this.recurring = recurring;
    }

    /** Guarda a imagem, lê com o modelo e devolve os dados prontos para o formulário de lançamento. */
    @Transactional
    public ReceiptScanResponse scan(User user, MultipartFile file, Long ignoreTransactionId) {
        byte[] original;
        try {
            original = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("Não foi possível ler a imagem enviada");
        }
        BufferedImage image = ReceiptStorage.decode(original);
        ReceiptStorage.Stored stored = storage.store(user.getId(), image);
        ReceiptScan scan = scanner.scan(stored.bytes());
        // O QR code é a fonte confiável da chave; o modelo de visão costuma pular ou inventar os 44 dígitos.
        String accessKey = AccessKey.decodeQr(image).orElse(scan.accessKey());
        // Data do lançamento: vencimento quando a fatura tem um; senão a emissão/compra; senão o dia da leitura.
        LocalDate date = scan.dueDate() != null ? scan.dueDate()
            : scan.issueDate() != null ? AccessKey.fixYear(scan.issueDate(), accessKey) : LocalDate.now();

        BigDecimal itemsTotal = scan.items().stream().map(ScannedItem::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = scan.total() != null && scan.total().signum() > 0 ? scan.total()
            : itemsTotal.signum() > 0 ? itemsTotal : null;

        CategorySuggester suggester = new CategorySuggester(
            categories.findByUserIdOrderByTypeAscNameAsc(user.getId()),
            rules.findByUserIdOrderByKeywordAsc(user.getId()));
        Optional<Category> category = suggester.suggest(scan.merchant(), TransactionType.EXPENSE, null);
        if (category.isEmpty() && !scan.items().isEmpty()) {
            String itemsText = scan.items().stream().map(ScannedItem::description).limit(10)
                .reduce("", (a, b) -> a + " " + b);
            category = suggester.suggest(itemsText, TransactionType.EXPENSE, null);
        }

        Optional<Transaction> duplicate = accessKey == null ? Optional.empty()
            : transactions.findFirstByUserIdAndReceiptAccessKey(user.getId(), accessKey);
        if (duplicate.isEmpty() && date != null && total != null) {
            // Sem chave legível, mesma data e mesmo valor já lançados valem como provável duplicata;
            // entre vários, prefere o do mesmo estabelecimento.
            List<Transaction> same = transactions.findByUserIdAndTypeAndDueDateAndAmount(user.getId(), TransactionType.EXPENSE, date, total)
                .stream().filter(t -> ignoreTransactionId == null || !t.getId().equals(ignoreTransactionId)).toList();
            duplicate = same.stream().max(java.util.Comparator.comparingInt((Transaction t) -> similarity(t, scan)).thenComparing(Transaction::getId, java.util.Comparator.reverseOrder()));
        }
        if (ignoreTransactionId != null) {
            duplicate = duplicate.filter(t -> !t.getId().equals(ignoreTransactionId));
        }

        // Sem duplicata e lançando algo novo: o comprovante pode ser o pagamento de um vencimento recorrente pendente.
        Transaction occurrence = null;
        if (duplicate.isEmpty() && ignoreTransactionId == null && total != null) {
            String text = scan.merchant() + " " + scan.items().stream().map(ScannedItem::description).limit(10)
                .reduce("", (a, b) -> a + " " + b);
            OccurrenceMatcher.Candidate cand = new OccurrenceMatcher.Candidate(text, total, TransactionType.EXPENSE, date);
            Map<OccurrenceMatcher.Candidate, ScoredOccurrence> found = recurring.matchPending(user, List.of(cand), c -> c,
                date, date);
            occurrence = found.isEmpty() ? null : found.get(cand).occurrence();
        }

        return new ReceiptScanResponse(stored.name(), scan.merchant(), date, scan.paid(), total, itemsTotal,
            scan.paymentMethod(), accessKey, scan.items(),
            category.map(Category::getId).orElse(null), category.map(Category::getName).orElse(null),
            duplicate.map(Transaction::getId).orElse(null), duplicate.map(Transaction::getDescription).orElse(null),
            duplicate.map(Transaction::getDueDate).orElse(null),
            occurrence == null ? null : occurrence.getId(), occurrence == null ? null : occurrence.getDescription(),
            occurrence == null ? null : occurrence.getDueDate(), occurrence == null ? null : occurrence.getAmount(),
            occurrence == null || occurrence.getCategory() == null ? null : occurrence.getCategory().getId(),
            occurrence == null ? null : occurrence.getAccount().getId());
    }

    /** Quanto um lançamento se parece com o comprovante lido: mesmo estabelecimento pesa 2, mesmos itens pesa 1. */
    private static int similarity(Transaction t, ReceiptScan scan) {
        return (sameMerchant(t, scan.merchant()) ? 2 : 0) + (sameItems(t, scan) ? 1 : 0);
    }

    /** Mesmos itens: mesma quantidade de linhas e mesmos valores (ordem indiferente). */
    private static boolean sameItems(Transaction t, ReceiptScan scan) {
        if (scan.items().isEmpty() || t.getItems().size() != scan.items().size()) {
            return false;
        }
        List<BigDecimal> a = t.getItems().stream().map(i -> i.getTotal().setScale(2, java.math.RoundingMode.HALF_UP)).sorted().toList();
        List<BigDecimal> b = scan.items().stream().map(i -> i.total().setScale(2, java.math.RoundingMode.HALF_UP)).sorted().toList();
        return a.equals(b);
    }

    private static boolean sameMerchant(Transaction t, String merchant) {
        if (merchant == null || merchant.isBlank()) {
            return false;
        }
        String a = merchant.trim().toLowerCase();
        return (t.getMerchant() != null && t.getMerchant().trim().toLowerCase().equals(a))
            || (t.getDescription() != null && t.getDescription().trim().toLowerCase().equals(a));
    }

    public Optional<byte[]> image(Long userId, String file) {
        return storage.load(userId, file);
    }
}
