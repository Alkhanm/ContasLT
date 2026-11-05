package br.com.contaslt.receipt;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import br.com.contaslt.common.BusinessException;
import br.com.contaslt.common.NotFoundException;
import br.com.contaslt.config.AppProperties;
import br.com.contaslt.receipt.ReceiptDtos.ReceiptRead;
import br.com.contaslt.receipt.ReceiptDtos.ReceiptScan;
import br.com.contaslt.user.User;

/**
 * Leitura de comprovantes: guarda a foto (JPEG reduzido), lê a chave do QR code e pede ao modelo de visão o conteúdo.
 * Devolve só o que foi lido; categoria sugerida, duplicata e vencimento recorrente são calculados no app, que tem os
 * dados. Com o provedor {@code worker} a leitura vai para a fila do computador do usuário e a resposta vem
 * {@code PENDING}; o app consulta {@link #job} até terminar (ou salva o lançamento e recebe o resultado depois).
 */
@Service
public class ReceiptService {

    private final ReceiptScanner scanner;
    private final ReceiptStorage storage;
    private final ReceiptJobService jobs;
    private final WorkerPresence presence;

    public ReceiptService(ReceiptScanner scanner, ReceiptStorage storage, ReceiptJobService jobs, WorkerPresence presence) {
        this.scanner = scanner;
        this.storage = storage;
        this.jobs = jobs;
        this.presence = presence;
    }

    @Transactional
    public ReceiptRead scan(User user, MultipartFile file) {
        jobs.requirePremium(user);
        BufferedImage image = ReceiptStorage.decode(bytes(file));
        ReceiptStorage.Stored stored = storage.store(user.getId(), image);
        // O QR code é a fonte confiável da chave; o modelo de visão costuma pular ou inventar os 44 dígitos.
        String qrKey = AccessKey.decodeQr(image).orElse(null);
        if (scanner.provider() == AppProperties.Receipts.Provider.WORKER) {
            ReceiptJob job = jobs.enqueue(user, stored.name(), qrKey, null);
            return new ReceiptRead(stored.name(), job.getId(), "PENDING", presence.online(), null, qrKey, null);
        }
        ReceiptScan scan = scanner.scan(stored.bytes());
        return new ReceiptRead(stored.name(), null, "DONE", presence.online(), null, qrKey, scan);
    }

    /** Estado de uma tarefa da fila; pronta, traz a leitura (e conta como entregue ao formulário). */
    @Transactional
    public ReceiptRead job(User user, Long jobId) {
        ReceiptJob job = jobs.find(user.getId(), jobId).orElseThrow(() -> new NotFoundException("Leitura não encontrada"));
        return switch (job.getStatus()) {
            case PENDING, RUNNING -> new ReceiptRead(job.getReceiptFile(), job.getId(), "PENDING", presence.online(), null,
                job.getQrAccessKey(), null);
            case FAILED -> new ReceiptRead(job.getReceiptFile(), job.getId(), "FAILED", presence.online(), job.getError(),
                job.getQrAccessKey(), null);
            case DONE -> {
                jobs.markDelivered(job);
                yield new ReceiptRead(job.getReceiptFile(), job.getId(), "DONE", presence.online(), null,
                    job.getQrAccessKey(), scanner.parse(job.getResult()));
            }
        };
    }

    /** Guarda uma foto sem ler (fotos de um backup importado). */
    public String upload(User user, MultipartFile file) {
        jobs.requirePremium(user);
        return storage.store(user.getId(), ReceiptStorage.decode(bytes(file))).name();
    }

    public Optional<byte[]> image(Long userId, String file) {
        return storage.load(userId, file);
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("Não foi possível ler a imagem enviada");
        }
    }
}
