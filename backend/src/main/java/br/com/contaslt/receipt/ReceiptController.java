package br.com.contaslt.receipt;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import br.com.contaslt.common.NotFoundException;
import br.com.contaslt.receipt.ReceiptDtos.AttachRequest;
import br.com.contaslt.receipt.ReceiptDtos.JobResult;
import br.com.contaslt.receipt.ReceiptDtos.ReceiptRead;
import br.com.contaslt.receipt.ReceiptDtos.Uploaded;
import br.com.contaslt.user.CurrentUser;
import jakarta.validation.Valid;

/**
 * Comprovantes: o único processamento que continua no servidor (foto, QR code e modelo de visão). Nada aqui lê ou
 * grava lançamentos; o app aplica as leituras nos dados dele.
 */
@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {

    private final ReceiptService receipts;
    private final ReceiptJobService jobs;
    private final CurrentUser currentUser;

    public ReceiptController(ReceiptService receipts, ReceiptJobService jobs, CurrentUser currentUser) {
        this.receipts = receipts;
        this.jobs = jobs;
        this.currentUser = currentUser;
    }

    /** Envia a foto; devolve o que foi lido (ou a tarefa na fila) e o nome do arquivo para anexar ao lançamento. */
    @PostMapping("/scan")
    public ReceiptRead scan(@RequestParam("file") MultipartFile file) {
        return receipts.scan(currentUser.get(), file);
    }

    @GetMapping("/jobs/{id}")
    public ReceiptRead job(@PathVariable Long id) {
        return receipts.job(currentUser.get(), id);
    }

    /** A foto foi salva num lançamento: liga a leitura a ele. Sem leitura para a foto, responde 204. */
    @PostMapping("/attach")
    public ResponseEntity<JobResult> attach(@Valid @RequestBody AttachRequest req) {
        return jobs.attach(currentUser.id(), req.receiptFile(), req.transactionId())
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Leituras terminadas de fotos já salvas em lançamentos, ainda não aplicadas pelo app. */
    @GetMapping("/results")
    public List<JobResult> results() {
        return jobs.undelivered(currentUser.id());
    }

    @PostMapping("/jobs/{id}/delivered")
    public ResponseEntity<Void> delivered(@PathVariable Long id) {
        jobs.markDelivered(currentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    /** "Ler de novo" a foto de um lançamento. */
    @PostMapping("/retry")
    public JobResult retry(@Valid @RequestBody AttachRequest req) {
        return jobs.retry(currentUser.get(), req.receiptFile(), req.transactionId());
    }

    /** Guarda uma foto sem ler (importação de backup). */
    @PostMapping
    public Uploaded upload(@RequestParam("file") MultipartFile file) {
        return new Uploaded(receipts.upload(currentUser.get(), file));
    }

    @GetMapping(value = "/{file}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> image(@PathVariable String file) {
        byte[] bytes = receipts.image(currentUser.id(), file)
            .orElseThrow(() -> new NotFoundException("Comprovante não encontrado"));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePrivate())
            .contentType(MediaType.IMAGE_JPEG).body(bytes);
    }
}
