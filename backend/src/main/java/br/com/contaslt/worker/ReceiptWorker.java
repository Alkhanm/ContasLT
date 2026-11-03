package br.com.contaslt.worker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import br.com.contaslt.common.BusinessException;
import br.com.contaslt.config.AppProperties;
import br.com.contaslt.receipt.ReceiptScanner;
import br.com.contaslt.receipt.ScannerUnavailableException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Worker da fila de comprovantes: roda no computador do usuário ({@code java -jar contaslt.jar --worker}), puxa as
 * fotos do servidor, lê cada uma com o Ollama local e devolve o JSON. Só faz chamadas de saída (HTTPS para o
 * backend e HTTP para o Ollama em localhost): nada precisa ficar exposto na internet.
 *
 * Variáveis: CONTASLT_URL (backend), WORKER_TOKEN (o mesmo do servidor), OLLAMA_URL, OLLAMA_MODEL, OLLAMA_TIMEOUT
 * e WORKER_POLL (segundos entre consultas à fila, padrão 10).
 */
public final class ReceiptWorker {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String base;
    private final String token;
    private final String ollamaUrl;
    private final int pollSeconds;
    private final ReceiptScanner scanner;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private ReceiptWorker(String base, String token, String ollamaUrl, String model, Duration timeout, int pollSeconds) {
        this.base = base.replaceAll("/+$", "");
        this.token = token;
        this.ollamaUrl = ollamaUrl.replaceAll("/+$", "");
        this.pollSeconds = pollSeconds;
        AppProperties props = new AppProperties(null, null,
            new AppProperties.Receipts(AppProperties.Receipts.Provider.OLLAMA, null),
            new AppProperties.Ollama(this.ollamaUrl, model, timeout), null, null, null, null);
        this.scanner = new ReceiptScanner(props, json);
    }

    public static void run() throws InterruptedException {
        String base = env("CONTASLT_URL", null);
        String token = env("WORKER_TOKEN", null);
        if (base == null || token == null) {
            System.err.println("Defina CONTASLT_URL (ex.: https://contaslt-production.up.railway.app) e WORKER_TOKEN (o mesmo do servidor).");
            System.exit(2);
        }
        new ReceiptWorker(base, token, env("OLLAMA_URL", "http://localhost:11434"), env("OLLAMA_MODEL", "qwen3.5:9b-q8_0"),
            Duration.parse(env("OLLAMA_TIMEOUT", "PT3M")), Integer.parseInt(env("WORKER_POLL", "10"))).loop();
    }

    private void loop() throws InterruptedException {
        log("Worker iniciado: fila em " + base + ", Ollama em " + ollamaUrl + ", modelo " + scanner.model());
        boolean ollamaWarned = false;
        boolean backendWarned = false;
        while (true) {
            try {
                if (!ollamaUp()) {
                    if (!ollamaWarned) {
                        log("Ollama não responde em " + ollamaUrl + "; esperando ele subir (nenhuma tarefa é pega enquanto isso).");
                        ollamaWarned = true;
                    }
                    ping();
                    Thread.sleep(Duration.ofSeconds(30));
                    continue;
                }
                ollamaWarned = false;
                JsonNode job = claim();
                backendWarned = false;
                if (job == null) {
                    Thread.sleep(Duration.ofSeconds(pollSeconds));
                    continue;
                }
                process(job.get("id").asLong(), job.get("receiptFile").asString());
            } catch (IOException e) {
                if (!backendWarned) {
                    log("Servidor indisponível (" + e.getMessage() + "); tentando de novo a cada 30 s.");
                    backendWarned = true;
                }
                Thread.sleep(Duration.ofSeconds(30));
            }
        }
    }

    private void process(long id, String file) throws IOException, InterruptedException {
        long start = System.currentTimeMillis();
        log("Tarefa " + id + ": lendo " + file);
        byte[] image = send(request("/api/worker/jobs/" + id + "/image").GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            .body();
        String content;
        try {
            content = scanner.ask(image);
        } catch (ScannerUnavailableException e) {
            log("Tarefa " + id + ": Ollama caiu no meio (" + e.getMessage() + "); devolvendo para a fila.");
            fail(id, e.getMessage(), true);
            Thread.sleep(Duration.ofSeconds(30));
            return;
        } catch (BusinessException e) {
            log("Tarefa " + id + ": falhou (" + e.getMessage() + ")");
            fail(id, e.getMessage(), false);
            return;
        }
        ObjectNode body = json.createObjectNode();
        body.put("content", content);
        send(request("/api/worker/jobs/" + id + "/result").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        log("Tarefa " + id + ": pronta em " + (System.currentTimeMillis() - start) / 1000 + " s");
    }

    private JsonNode claim() throws IOException, InterruptedException {
        HttpResponse<String> r = send(request("/api/worker/jobs/claim").POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 204 ? null : json.readTree(r.body());
    }

    private void ping() throws InterruptedException {
        try {
            send(request("/api/worker/ping").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // só sinal de vida; o próximo claim avisa se o servidor estiver fora
        }
    }

    private void fail(long id, String error, boolean retryable) throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.put("error", error);
        body.put("retryable", retryable);
        send(request("/api/worker/jobs/" + id + "/fail").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private boolean ollamaUp() {
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(ollamaUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding());
            return r.statusCode() < 500;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(60))
            .header("X-Worker-Token", token);
    }

    /** Envia e trata qualquer resposta de erro como IOException, para o laço principal esperar e tentar de novo. */
    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        HttpResponse<T> r = http.send(req, handler);
        if (r.statusCode() == 401) {
            throw new IOException("o servidor recusou o WORKER_TOKEN (401)");
        }
        if (r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + " em " + req.uri().getPath());
        }
        return r;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static void log(String message) {
        System.out.println(LocalTime.now().format(TIME) + " [worker] " + message);
    }
}
