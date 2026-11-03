package br.com.contaslt.receipt;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.contaslt.category.Category;
import br.com.contaslt.category.CategoryRepository;
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.common.ForbiddenException;
import br.com.contaslt.config.AppProperties;
import br.com.contaslt.common.NotFoundException;
import br.com.contaslt.common.TransactionType;
import br.com.contaslt.imports.CategoryRuleRepository;
import br.com.contaslt.imports.CategorySuggester;
import br.com.contaslt.receipt.ReceiptDtos.ClaimedJob;
import br.com.contaslt.receipt.ReceiptDtos.ReceiptScan;
import br.com.contaslt.receipt.ReceiptDtos.ScannedItem;
import br.com.contaslt.receipt.ReceiptJob.Status;
import br.com.contaslt.transaction.ReceiptScanStatus;
import br.com.contaslt.transaction.Transaction;
import br.com.contaslt.transaction.TransactionItem;
import br.com.contaslt.user.User;

/**
 * Fila de leitura de comprovantes feita por um worker no computador do usuário. O servidor guarda a foto e a tarefa;
 * o worker puxa ({@link #claim}), lê com o Ollama local e devolve o JSON ({@link #complete}). Se o lançamento já foi
 * salvo com a foto enquanto a leitura esperava, o resultado é aplicado nele: itens, estabelecimento, chave e categoria
 * sugerida. Total ou data diferentes do lançado viram um aviso ({@link ReceiptScanStatus#DIVERGENT}) para o usuário decidir.
 */
@Service
public class ReceiptJobService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptJobService.class);

    /** Quanto tempo o worker tem para devolver uma tarefa antes de ela voltar para a fila. */
    static final java.time.Duration LEASE = java.time.Duration.ofMinutes(5);
    /** Falhas do modelo toleradas antes de desistir da foto. */
    static final int MAX_ATTEMPTS = 3;

    private final ReceiptJobRepository jobs;
    private final ReceiptStorage storage;
    private final ReceiptScanner scanner;
    private final CategoryRepository categories;
    private final CategoryRuleRepository rules;
    private final boolean requirePremium;

    public ReceiptJobService(ReceiptJobRepository jobs, ReceiptStorage storage, ReceiptScanner scanner,
            CategoryRepository categories, CategoryRuleRepository rules, AppProperties props) {
        this.jobs = jobs;
        this.storage = storage;
        this.scanner = scanner;
        this.categories = categories;
        this.rules = rules;
        this.requirePremium = props.receipts() == null || props.receipts().requirePremiumOrDefault();
    }

    /**
     * A leitura de comprovantes gasta a GPU do dono do app: só usuários marcados como premium no banco podem
     * enfileirar fotos (403 para os demais). Vale para o envio e para o "tentar de novo".
     */
    public void requirePremium(User user) {
        if (requirePremium && !user.isPremium()) {
            throw new ForbiddenException("A leitura de comprovantes não está liberada para a sua conta.");
        }
    }

    @Transactional
    public ReceiptJob enqueue(User user, String receiptFile, String qrAccessKey, Transaction transaction) {
        ReceiptJob job = new ReceiptJob();
        job.setUser(user);
        job.setReceiptFile(receiptFile);
        job.setQrAccessKey(qrAccessKey);
        job.setTransaction(transaction);
        return jobs.save(job);
    }

    public Optional<ReceiptJob> find(Long userId, Long jobId) {
        return jobs.findByIdAndUserId(jobId, userId);
    }

    @Transactional
    public void markDelivered(ReceiptJob job) {
        job.setDelivered(true);
        jobs.save(job);
    }

    // ---------- Lado do worker ----------

    /** Próxima tarefa da fila (ou uma em execução com prazo vencido), já reservada para o worker. */
    @Transactional
    public Optional<ClaimedJob> claim() {
        LocalDateTime now = LocalDateTime.now();
        List<ReceiptJob> next = jobs.findClaimable(Status.PENDING, Status.RUNNING, now, PageRequest.of(0, 1));
        if (next.isEmpty()) {
            return Optional.empty();
        }
        ReceiptJob job = next.get(0);
        job.setStatus(Status.RUNNING);
        job.setAttempts(job.getAttempts() + 1);
        job.setLeasedUntil(now.plus(LEASE));
        return Optional.of(new ClaimedJob(job.getId(), job.getReceiptFile()));
    }

    /** A foto de uma tarefa reservada. */
    @Transactional(readOnly = true)
    public byte[] image(Long jobId) {
        ReceiptJob job = running(jobId);
        return storage.load(job.getUser().getId(), job.getReceiptFile())
            .orElseThrow(() -> new NotFoundException("Imagem do comprovante não encontrada"));
    }

    /** O worker devolveu o JSON do modelo: guarda e, se já houver lançamento salvo com a foto, aplica nele. */
    @Transactional
    public void complete(Long jobId, String content) {
        ReceiptJob job = running(jobId);
        ReceiptScan scan;
        try {
            scan = scanner.parse(content);
        } catch (BusinessException e) {
            fail(job, e.getMessage(), false);
            return;
        }
        job.setStatus(Status.DONE);
        job.setResult(content);
        job.setError(null);
        job.setLeasedUntil(null);
        job.setFinishedAt(LocalDateTime.now());
        Transaction t = job.getTransaction();
        if (t != null) {
            applyTo(t, scan, job.getQrAccessKey());
            job.setDelivered(true);
        }
    }

    /** O worker não conseguiu ler. Erro do ambiente (Ollama fora) volta para a fila sem gastar tentativa. */
    @Transactional
    public void fail(Long jobId, String error, boolean retryable) {
        fail(running(jobId), error, retryable);
    }

    private void fail(ReceiptJob job, String error, boolean retryable) {
        job.setError(error == null ? null : error.substring(0, Math.min(500, error.length())));
        job.setLeasedUntil(null);
        if (retryable) {
            job.setAttempts(Math.max(0, job.getAttempts() - 1));
            job.setStatus(Status.PENDING);
            return;
        }
        if (job.getAttempts() >= MAX_ATTEMPTS) {
            job.setStatus(Status.FAILED);
            job.setFinishedAt(LocalDateTime.now());
            Transaction t = job.getTransaction();
            if (t != null) {
                t.setReceiptScanStatus(ReceiptScanStatus.FAILED);
            }
            log.warn("Leitura do comprovante {} desistida após {} tentativas: {}", job.getReceiptFile(), job.getAttempts(), error);
        } else {
            job.setStatus(Status.PENDING);
        }
    }

    private ReceiptJob running(Long jobId) {
        ReceiptJob job = jobs.findById(jobId).orElseThrow(() -> new NotFoundException("Tarefa não encontrada"));
        if (job.getStatus() != Status.RUNNING) {
            throw new BusinessException("A tarefa " + jobId + " não está reservada para o worker (" + job.getStatus() + ")");
        }
        return job;
    }

    // ---------- Lado do lançamento ----------

    /**
     * Um lançamento acabou de ser salvo com esta foto. Se a leitura ainda não aconteceu, fica marcado como pendente
     * e recebe os itens quando ela terminar; se já terminou e o formulário não chegou a ver o resultado, aplica agora.
     */
    @Transactional
    public void attach(Long userId, Transaction t) {
        Optional<ReceiptJob> found = jobs.findFirstByUserIdAndReceiptFileOrderByIdDesc(userId, t.getReceiptFile());
        if (found.isEmpty()) {
            return;
        }
        ReceiptJob job = found.get();
        job.setTransaction(t);
        switch (job.getStatus()) {
            case PENDING, RUNNING -> t.setReceiptScanStatus(ReceiptScanStatus.PENDING);
            case FAILED -> t.setReceiptScanStatus(ReceiptScanStatus.FAILED);
            case DONE -> {
                if (!job.isDelivered()) {
                    applyTo(t, scanner.parse(job.getResult()), job.getQrAccessKey());
                    job.setDelivered(true);
                }
            }
        }
    }

    @Transactional
    public void resolve(User user, Transaction t, ReceiptScanAction action) {
        switch (action) {
            case ACCEPT -> {
                if (t.getReceiptScanStatus() != ReceiptScanStatus.DIVERGENT) {
                    throw new BusinessException("Este lançamento não tem leitura divergente para aplicar");
                }
                if (t.getReceiptScanTotal() != null) {
                    t.setAmount(t.getReceiptScanTotal());
                }
                if (t.getReceiptScanDate() != null) {
                    t.setDueDate(t.getReceiptScanDate());
                }
                t.clearReceiptScan();
            }
            case DISMISS -> t.clearReceiptScan();
            case RETRY -> {
                requirePremium(user);
                if (t.getReceiptFile() == null) {
                    throw new BusinessException("Este lançamento não tem comprovante para ler");
                }
                String qr = storage.load(user.getId(), t.getReceiptFile())
                    .flatMap(bytes -> {
                        try {
                            return AccessKey.decodeQr(ReceiptStorage.decode(bytes));
                        } catch (BusinessException e) {
                            return Optional.<String>empty();
                        }
                    })
                    .orElse(null);
                t.clearReceiptScan();
                t.setReceiptScanStatus(ReceiptScanStatus.PENDING);
                enqueue(user, t.getReceiptFile(), qr, t);
            }
        }
    }

    /** Leva o que o modelo leu para um lançamento já salvo, sem sobrescrever o que o usuário preencheu. */
    void applyTo(Transaction t, ReceiptScan scan, String qrAccessKey) {
        String key = qrAccessKey != null ? qrAccessKey : scan.accessKey();
        if (t.getItems().isEmpty() && !scan.items().isEmpty()) {
            List<TransactionItem> items = new ArrayList<>();
            for (ScannedItem i : scan.items()) {
                TransactionItem item = new TransactionItem();
                item.setDescription(i.description().length() > 140 ? i.description().substring(0, 140) : i.description());
                item.setQuantity(i.quantity());
                item.setUnitPrice(i.unitPrice());
                item.setTotal(i.total());
                items.add(item);
            }
            t.replaceItems(items);
        }
        if (isBlank(t.getMerchant()) && !isBlank(scan.merchant())) {
            t.setMerchant(scan.merchant().length() > 120 ? scan.merchant().substring(0, 120) : scan.merchant());
        }
        if (t.getReceiptAccessKey() == null && key != null) {
            t.setReceiptAccessKey(key);
        }
        if (t.getCategory() == null && t.getType() == TransactionType.EXPENSE) {
            suggestCategory(t.getUser().getId(), scan).ifPresent(t::setCategory);
        }

        BigDecimal itemsTotal = scan.items().stream().map(ScannedItem::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = scan.total() != null && scan.total().signum() > 0 ? scan.total()
            : itemsTotal.signum() > 0 ? itemsTotal : null;
        LocalDate date = scan.dueDate() != null ? scan.dueDate()
            : scan.issueDate() != null ? AccessKey.fixYear(scan.issueDate(), key) : null;
        boolean totalDiffers = total != null && total.compareTo(t.getAmount()) != 0;
        boolean dateDiffers = date != null && !date.equals(t.getDueDate());
        t.clearReceiptScan();
        if (totalDiffers || dateDiffers) {
            t.setReceiptScanStatus(ReceiptScanStatus.DIVERGENT);
            t.setReceiptScanTotal(totalDiffers ? total : null);
            t.setReceiptScanDate(dateDiffers ? date : null);
        }
    }

    /** Categoria pelas regras do usuário: primeiro pelo estabelecimento, depois pelos itens. */
    Optional<Category> suggestCategory(Long userId, ReceiptScan scan) {
        CategorySuggester suggester = new CategorySuggester(
            categories.findByUserIdOrderByTypeAscNameAsc(userId), rules.findByUserIdOrderByKeywordAsc(userId));
        Optional<Category> category = suggester.suggest(scan.merchant(), TransactionType.EXPENSE, null);
        if (category.isEmpty() && !scan.items().isEmpty()) {
            String itemsText = scan.items().stream().map(ScannedItem::description).limit(10)
                .reduce("", (a, b) -> a + " " + b);
            category = suggester.suggest(itemsText, TransactionType.EXPENSE, null);
        }
        return category;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
