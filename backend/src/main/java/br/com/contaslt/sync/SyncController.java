package br.com.contaslt.sync;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.contaslt.sync.SyncDtos.PullResponse;
import br.com.contaslt.sync.SyncDtos.PushRequest;
import br.com.contaslt.sync.SyncDtos.PushResponse;
import br.com.contaslt.user.CurrentUser;
import jakarta.validation.Valid;

/** Sincronização dos clientes local-first: o servidor só guarda os documentos e entrega o que mudou. */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncService sync;
    private final CurrentUser currentUser;

    public SyncController(SyncService sync, CurrentUser currentUser) {
        this.sync = sync;
        this.currentUser = currentUser;
    }

    @GetMapping("/pull")
    public PullResponse pull(@RequestParam(defaultValue = "0") long since,
            @RequestParam(defaultValue = "1000") int limit) {
        return sync.pull(currentUser.id(), since, limit);
    }

    @PostMapping("/push")
    public PushResponse push(@Valid @RequestBody PushRequest req) {
        return sync.push(currentUser.id(), req.changes());
    }
}
