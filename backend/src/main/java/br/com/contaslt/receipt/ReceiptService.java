package br.com.contaslt.receipt;

import java.io.IOException;
import java.math.BigDecimal;
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

    public ReceiptService(ReceiptScanner scanner, ReceiptStorage storage, CategoryRepository categories,
            CategoryRuleRepository rules, TransactionRepository transactions) {
        this.scanner = scanner;
        this.storage = storage;
        this.categories = categories;
        this.rules = rules;
        this.transactions = transactions;
    }

    /** Guarda a imagem, lê com o modelo e devolve os dados prontos para o formulário de lançamento. */
    @Transactional(readOnly = true)
    public ReceiptScanResponse scan(User user, MultipartFile file) {
        byte[] original;
        try {
            original = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("Não foi possível ler a imagem enviada");
        }
        ReceiptStorage.Stored stored = storage.store(user.getId(), original);
        ReceiptScan scan = scanner.scan(stored.bytes());

        BigDecimal itemsTotal = scan.items().stream().map(ScannedItem::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = scan.total() != null && scan.total().signum() > 0 ? scan.total()
            : itemsTotal.signum() > 0 ? itemsTotal : null;

        CategorySuggester suggester = new CategorySuggester(
            categories.findByUserIdOrderByTypeAscNameAsc(user.getId()),
            rules.findByUserIdOrderByKeywordAsc(user.getId()),
            transactions.findRecentCategorized(user.getId()));
        Optional<Category> category = suggester.suggest(scan.merchant(), TransactionType.EXPENSE, null);
        if (category.isEmpty() && !scan.items().isEmpty()) {
            String itemsText = scan.items().stream().map(ScannedItem::description).limit(10)
                .reduce("", (a, b) -> a + " " + b);
            category = suggester.suggest(itemsText, TransactionType.EXPENSE, null);
        }

        Optional<Transaction> duplicate = scan.accessKey() == null ? Optional.empty()
            : transactions.findFirstByUserIdAndReceiptAccessKey(user.getId(), scan.accessKey());

        return new ReceiptScanResponse(stored.name(), scan.merchant(), scan.date(), total, itemsTotal,
            scan.paymentMethod(), scan.accessKey(), scan.items(),
            category.map(Category::getId).orElse(null), category.map(Category::getName).orElse(null),
            duplicate.map(Transaction::getId).orElse(null), duplicate.map(Transaction::getDescription).orElse(null),
            duplicate.map(Transaction::getDueDate).orElse(null));
    }

    public Optional<byte[]> image(Long userId, String file) {
        return storage.load(userId, file);
    }
}
