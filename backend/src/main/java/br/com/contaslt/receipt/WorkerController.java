package br.com.contaslt.receipt;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.contaslt.receipt.ReceiptDtos.ClaimedJob;
import br.com.contaslt.receipt.ReceiptDtos.WorkerFailure;
import br.com.contaslt.receipt.ReceiptDtos.WorkerResult;
import jakarta.validation.Valid;

/**
 * Rotas do worker que roda no computador do usuário. Não usam JWT: o {@code X-Worker-Token} é conferido pelo
 * {@link br.com.contaslt.config.WorkerAuthFilter}. Toda chamada conta como sinal de vida do worker.
 */
@RestController
@RequestMapping("/api/worker")
public class WorkerController {

    private final ReceiptJobService jobs;
    private final WorkerPresence presence;

    public WorkerController(ReceiptJobService jobs, WorkerPresence presence) {
        this.jobs = jobs;
        this.presence = presence;
    }

    @PostMapping("/ping")
    public ResponseEntity<Void> ping() {
        presence.seen();
        return ResponseEntity.noContent().build();
    }

    /** Reserva a próxima tarefa; 204 quando a fila está vazia. */
    @PostMapping("/jobs/claim")
    public ResponseEntity<ClaimedJob> claim() {
        presence.seen();
        return jobs.claim().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping(value = "/jobs/{id}/image", produces = MediaType.IMAGE_JPEG_VALUE)
    public byte[] image(@PathVariable Long id) {
        presence.seen();
        return jobs.image(id);
    }

    @PostMapping("/jobs/{id}/result")
    public ResponseEntity<Void> result(@PathVariable Long id, @Valid @RequestBody WorkerResult body) {
        presence.seen();
        jobs.complete(id, body.content());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/jobs/{id}/fail")
    public ResponseEntity<Void> fail(@PathVariable Long id, @RequestBody WorkerFailure body) {
        presence.seen();
        jobs.fail(id, body.error(), body.retryable());
        return ResponseEntity.noContent().build();
    }
}
