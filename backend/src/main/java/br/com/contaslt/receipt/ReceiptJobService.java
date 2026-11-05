package br.com.contaslt.receipt;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.contaslt.common.BusinessException;
import br.com.contaslt.common.NotFoundException;
import br.com.contaslt.receipt.ReceiptDtos.ClaimedJob;
import br.com.contaslt.receipt.ReceiptDtos.JobResult;
import br.com.contaslt.receipt.ReceiptDtos.ReceiptScan;
import br.com.contaslt.receipt.ReceiptJob.Status;
import br.com.contaslt.user.PremiumGuard;
import br.com.contaslt.user.User;

/**
 * Fila de leitura de comprovantes feita por um worker no computador do usuário. O servidor guarda a foto e a tarefa;
 * o worker puxa ({@link #claim}), lê com o Ollama local e devolve o JSON ({@link #complete}). O servidor não mexe em
 * lançamentos: quando a foto já foi salva num lançamento do app ({@link #attach}), o resultado fica esperando o app
 * buscá-lo ({@link #undelivered}), aplicar nos dados locais e confirmar ({@link #markDelivered}).
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
    private final PremiumGuard premium;

    public ReceiptJobService(ReceiptJobRepository jobs, ReceiptStorage storage, ReceiptScanner scanner, PremiumGuard premium) {
        this.jobs = jobs;
        this.storage = storage;
        this.scanner = scanner;
        this.premium = premium;
    }

    /** A leitura gasta a GPU do dono do app: só premium enfileira fotos (envio e "tentar de novo"). */
    public void requirePremium(User user) {
        premium.require(user, "A leitura de comprovantes não está liberada para a sua conta.");
    }

    @Transactional
    public ReceiptJob enqueue(User user, String receiptFile, String qrAccessKey, UUID transactionUuid) {
        ReceiptJob job = new ReceiptJob();
        job.setUser(user);
        job.setReceiptFile(receiptFile);
        job.setQrAccessKey(qrAccessKey);
        job.setTransactionUuid(transactionUuid);
        return jobs.save(job);
    }

    public Optional<ReceiptJob> find(Long userId, Long jobId) {
        return jobs.findByIdAndUserId(jobId, userId);
    }

    @Transactional
    public void markDelivered(Long userId, Long jobId) {
        ReceiptJob job = find(userId, jobId).orElseThrow(() -> new NotFoundException("Leitura não encontrada"));
        job.setDelivered(true);
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

    /** O worker devolveu o JSON do modelo: guarda para o app buscar. */
    @Transactional
    public void complete(Long jobId, String content) {
        ReceiptJob job = running(jobId);
        try {
            scanner.parse(content);
        } catch (BusinessException e) {
            fail(job, e.getMessage(), false);
            return;
        }
        job.setStatus(Status.DONE);
        job.setResult(content);
        job.setError(null);
        job.setLeasedUntil(null);
        job.setFinishedAt(LocalDateTime.now());
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

    // ---------- Lado do app ----------

    /**
     * Um lançamento do app acabou de ser salvo com esta foto. Liga a leitura mais recente da foto a ele e devolve o
     * estado: PENDING (ainda na fila), FAILED, ou DONE com o resultado se o formulário não chegou a vê-lo (o app aplica
     * na hora e a leitura conta como entregue). Sem leitura para a foto, devolve vazio.
     */
    @Transactional
    public Optional<JobResult> attach(Long userId, String receiptFile, UUID transactionUuid) {
        Optional<ReceiptJob> found = jobs.findFirstByUserIdAndReceiptFileOrderByIdDesc(userId, receiptFile);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ReceiptJob job = found.get();
        job.setTransactionUuid(transactionUuid);
        JobResult result = toResult(job, job.getStatus() == Status.DONE && !job.isDelivered());
        if (job.getStatus() == Status.DONE || job.getStatus() == Status.FAILED) {
            job.setDelivered(true);
        }
        return Optional.of(result);
    }

    /** Leituras terminadas de fotos já salvas em lançamentos, que o app ainda não aplicou. */
    @Transactional(readOnly = true)
    public List<JobResult> undelivered(Long userId) {
        return jobs.findUndelivered(userId).stream().map(j -> toResult(j, j.getStatus() == Status.DONE)).toList();
    }

    /** "Ler de novo": a foto do lançamento volta para a fila. */
    @Transactional
    public JobResult retry(User user, String receiptFile, UUID transactionUuid) {
        requirePremium(user);
        byte[] bytes = storage.load(user.getId(), receiptFile)
            .orElseThrow(() -> new NotFoundException("Comprovante não encontrado"));
        String qr;
        try {
            qr = AccessKey.decodeQr(ReceiptStorage.decode(bytes)).orElse(null);
        } catch (BusinessException e) {
            qr = null;
        }
        return toResult(enqueue(user, receiptFile, qr, transactionUuid), false);
    }

    private JobResult toResult(ReceiptJob job, boolean withScan) {
        ReceiptScan scan = withScan && job.getResult() != null ? scanner.parse(job.getResult()) : null;
        String status = switch (job.getStatus()) {
            case PENDING, RUNNING -> "PENDING";
            case DONE -> "DONE";
            case FAILED -> "FAILED";
        };
        return new JobResult(job.getId(), job.getTransactionUuid(), job.getReceiptFile(), status, job.getError(),
            job.getQrAccessKey(), scan);
    }
}
