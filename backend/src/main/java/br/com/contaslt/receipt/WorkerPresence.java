package br.com.contaslt.receipt;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

/** Última vez que o worker do computador falou com o servidor; "online" = passou por aqui há menos de 45 s. */
@Component
public class WorkerPresence {

    static final Duration ONLINE_WINDOW = Duration.ofSeconds(45);

    private volatile Instant lastSeen;

    public void seen() {
        lastSeen = Instant.now();
    }

    public boolean online() {
        Instant seen = lastSeen;
        return seen != null && Duration.between(seen, Instant.now()).compareTo(ONLINE_WINDOW) < 0;
    }
}
