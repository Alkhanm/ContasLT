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
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.common.NotFoundException;
import br.com.contaslt.config.AppProperties;
import br.com.contaslt.common.TransactionType;
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
    private final ReceiptJobService jobs;
    private final WorkerPresence presence;
    private final TransactionRepository transactions;
    private final RecurringBillService recurring;

    public ReceiptService(ReceiptScanner scanner, ReceiptStorage storage, ReceiptJobService jobs, WorkerPresence presence,
            RecurringBillService recurring, TransactionRepository transactions) {
        this.scanner = scanner;
        this.storage = storage;
        this.jobs = jobs;
        this.presence = presence;
        this.transactions = transactions;
        this.recurring = recurring;
    }

    /**
     * Guarda a imagem e lê com o modelo, devolvendo os dados prontos para o formulário. Com o provedor {@code worker}
     * a leitura fica numa fila para o computador do usuário: a resposta vem só com o arquivo e {@code status=PENDING},
     * e o formulário consulta {@link #job} até o resultado chegar (ou salva o lançamento e recebe os itens depois).
     */
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
        // O QR code é a fonte confiável da chave; o modelo de visão costuma pular ou inventar os 44 dígitos.
        String qrKey = AccessKey.decodeQr(image).orElse(null);
        if (scanner.provider() == AppProperties.Receipts.Provider.WORKER) {
            ReceiptJob job = jobs.enqueue(user, stored.name(), qrKey, null);
            return ReceiptScanResponse.pending(job.getId(), stored.name(), presence.online());
        }
        ReceiptScan scan = scanner.scan(stored.bytes());
        return complete(user, stored.name(), scan, qrKey, ignoreTransactionId, null);
    }

    /** Estado de uma tarefa da fila; quando pronta, a mesma resposta que a leitura direta daria. */
    @Transactional
    public ReceiptScanResponse job(User user, Long jobId, Long ignoreTransactionId) {
        ReceiptJob job = jobs.find(user.getId(), jobId).orElseThrow(() -> new NotFoundException("Leitura não encontrada"));
        return switch (job.getStatus()) {
            case PENDING, RUNNING -> ReceiptScanResponse.pending(job.getId(), job.getReceiptFile(), presence.online());
            case FAILED -> ReceiptScanResponse.failed(job.getId(), job.getReceiptFile(), presence.online(), job.getError());
            case DONE -> {
                ReceiptScan scan = scanner.parse(job.getResult());
                jobs.markDelivered(job);
                yield complete(user, job.getReceiptFile(), scan, job.getQrAccessKey(), ignoreTransactionId, job.getId());
            }
        };
    }

    /** Monta a resposta do formulário a partir do que o modelo leu: data, total, categoria, duplicata e recorrente. */
    private ReceiptScanResponse complete(User user, String receiptFile, ReceiptScan scan, String qrKey,
            Long ignoreTransactionId, Long jobId) {
        String accessKey = qrKey != null ? qrKey : scan.accessKey();
        // Data do lançamento: vencimento quando a fatura tem um; senão a emissão/compra; senão o dia da leitura.
        LocalDate date = scan.dueDate() != null ? scan.dueDate()
            : scan.issueDate() != null ? AccessKey.fixYear(scan.issueDate(), accessKey) : LocalDate.now();

        BigDecimal itemsTotal = scan.items().stream().map(ScannedItem::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = scan.total() != null && scan.total().signum() > 0 ? scan.total()
            : itemsTotal.signum() > 0 ? itemsTotal : null;

        Optional<Category> category = jobs.suggestCategory(user.getId(), scan);

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

        return new ReceiptScanResponse(receiptFile, scan.merchant(), date, scan.paid(), total, itemsTotal,
            scan.paymentMethod(), accessKey, scan.items(),
            category.map(Category::getId).orElse(null), category.map(Category::getName).orElse(null),
            duplicate.map(Transaction::getId).orElse(null), duplicate.map(Transaction::getDescription).orElse(null),
            duplicate.map(Transaction::getDueDate).orElse(null),
            occurrence == null ? null : occurrence.getId(), occurrence == null ? null : occurrence.getDescription(),
            occurrence == null ? null : occurrence.getDueDate(), occurrence == null ? null : occurrence.getAmount(),
            occurrence == null || occurrence.getCategory() == null ? null : occurrence.getCategory().getId(),
            occurrence == null ? null : occurrence.getAccount().getId(),
            jobId, "DONE", presence.online(), null);
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
