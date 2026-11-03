package br.com.contaslt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import br.com.contaslt.common.BusinessException;
import br.com.contaslt.config.AppProperties;
import br.com.contaslt.config.AppProperties.Receipts.Provider;
import br.com.contaslt.receipt.ReceiptDtos.ReceiptScan;
import br.com.contaslt.receipt.ReceiptScanner;
import br.com.contaslt.receipt.ReceiptStorage;
import tools.jackson.databind.ObjectMapper;

/** Os provedores de producao (Supabase Storage e API compativel com OpenAI) contra um servidor HTTP falso. */
class ReceiptProvidersTest {

    private HttpServer server;
    private String base;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private boolean bucketExists;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        byte[] body = ex.getRequestBody().readAllBytes();
        calls.add(method + " " + path + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
        assertThat(ex.getRequestHeaders().getFirst("apikey")).isIn("eyJhbGciOi.service.key", "sb_secret_abc123", null);
        if (path.equals("/storage/v1/bucket") && method.equals("POST")) {
            bucketExists = true;
            reply(ex, 200, "{\"name\":\"receipts\"}");
        } else if (path.startsWith("/storage/v1/object/authenticated/receipts/")) {
            byte[] found = objects.get(path.substring("/storage/v1/object/authenticated/receipts/".length()));
            if (found == null) {
                reply(ex, 404, "{\"error\":\"not_found\"}");
            } else {
                ex.sendResponseHeaders(200, found.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(found);
                }
            }
        } else if (path.startsWith("/storage/v1/object/receipts/") && method.equals("POST")) {
            if (!bucketExists) {
                reply(ex, 404, "{\"statusCode\":\"404\",\"error\":\"Bucket not found\",\"message\":\"Bucket not found\"}");
            } else {
                objects.put(path.substring("/storage/v1/object/receipts/".length()), body);
                reply(ex, 200, "{\"Key\":\"receipts/x\"}");
            }
        } else if (path.equals("/v1/chat/completions") && method.equals("POST")) {
            String req = new String(body, StandardCharsets.UTF_8);
            assertThat(req).contains("\"json_schema\"").contains("data:image/jpeg;base64,").contains("\"model\":\"vision-test\"");
            reply(ex, 200, """
                {"choices":[{"message":{"role":"assistant","content":"{\\"merchant\\":\\"PADARIA\\",\\"issueDate\\":\\"2026-11-02\\",\\"dueDate\\":\\"\\",\\"paid\\":true,\\"total\\":12.5,\\"paymentMethod\\":\\"Pix\\",\\"accessKey\\":\\"\\",\\"items\\":[{\\"description\\":\\"PAO\\",\\"quantity\\":2,\\"unitPrice\\":1.25,\\"total\\":2.5}]}"}}]}
                """);
        } else {
            reply(ex, 500, "rota inesperada " + method + " " + path);
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private AppProperties props(Provider provider) {
        return new AppProperties(null, null, new AppProperties.Receipts(provider),
            new AppProperties.Ollama("http://127.0.0.1:1", "x", Duration.ofSeconds(1)),
            new AppProperties.OpenAi(base + "/v1/", "sk-test", "vision-test", Duration.ofSeconds(5)),
            new AppProperties.Storage("/tmp", new AppProperties.Storage.Supabase(base, "eyJhbGciOi.service.key", "receipts")), null, null, null);
    }

    @Test
    void supabaseStorageCreatesBucketOnFirstWriteAndReadsBack() {
        ReceiptStorage storage = new ReceiptStorage(props(Provider.NONE));
        var img = new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB);

        ReceiptStorage.Stored stored = storage.store(7L, img);

        assertThat(stored.name()).startsWith("u7-").endsWith(".jpg");
        assertThat(calls).anyMatch(c -> c.startsWith("POST /storage/v1/bucket"))
            .allMatch(c -> c.endsWith("auth=Bearer eyJhbGciOi.service.key"));
        assertThat(storage.load(7L, stored.name())).contains(stored.bytes());
        assertThat(storage.load(8L, stored.name())).isEmpty();               // de outro usuario
        assertThat(storage.load(7L, "u7-" + "0".repeat(36) + ".jpg")).isEmpty(); // inexistente
    }

    @Test
    void newSecretKeyGoesOnlyInTheApikeyHeader() {
        var cfg = new AppProperties(null, null, new AppProperties.Receipts(Provider.NONE), null, null,
            new AppProperties.Storage("/tmp", new AppProperties.Storage.Supabase(base, "sb_secret_abc123", "receipts")), null, null, null);
        ReceiptStorage storage = new ReceiptStorage(cfg);
        var img = new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB);
        storage.store(4L, img);
        assertThat(calls).isNotEmpty().allMatch(c -> c.endsWith("auth=null"));   // sem Authorization: Bearer
    }

    @Test
    void supabaseUrlPastedFromTheS3PageStillHitsTheRestApi() {
        var cfg = new AppProperties(null, null, new AppProperties.Receipts(Provider.NONE), null, null,
            new AppProperties.Storage("/tmp", new AppProperties.Storage.Supabase(base + "/storage/v1/s3", "eyJhbGciOi.service.key", "receipts")), null, null, null);
        ReceiptStorage storage = new ReceiptStorage(cfg);
        var img = new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ReceiptStorage.Stored stored = storage.store(3L, img);
        assertThat(calls).anyMatch(c -> c.startsWith("POST /storage/v1/object/receipts/" + stored.name()));
        assertThat(storage.load(3L, stored.name())).contains(stored.bytes());
    }

    @Test
    void openAiCompatibleProviderParsesChatCompletion() {
        ReceiptScanner scanner = new ReceiptScanner(props(Provider.OPENAI), new ObjectMapper());

        ReceiptScan scan = scanner.scan(new byte[] {1, 2, 3});

        assertThat(scan.merchant()).isEqualTo("PADARIA");
        assertThat(scan.total()).isEqualByComparingTo("12.5");
        assertThat(scan.items()).hasSize(1);
        assertThat(calls).anyMatch(c -> c.equals("POST /v1/chat/completions auth=Bearer sk-test"));
    }

    @Test
    void disabledProviderFailsWithClearMessage() {
        ReceiptScanner scanner = new ReceiptScanner(props(Provider.NONE), new ObjectMapper());
        assertThatThrownBy(() -> scanner.scan(new byte[] {1}))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("não está disponível");
        assertThat(calls).isEmpty();
    }
}
