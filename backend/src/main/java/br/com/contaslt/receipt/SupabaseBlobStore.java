package br.com.contaslt.receipt;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Comprovantes num bucket privado do Supabase Storage, pela API REST ({@code /storage/v1}) com a chave
 * {@code service_role}: o backend é o único cliente, então não há política RLS a configurar.
 * O bucket é criado na primeira gravação, se ainda não existir.
 */
final class SupabaseBlobStore implements ReceiptBlobStore {

    private static final Logger log = LoggerFactory.getLogger(SupabaseBlobStore.class);

    private final String base;
    private final String key;
    private final String bucket;
    private final HttpClient http;

    SupabaseBlobStore(String projectUrl, String key, String bucket) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("SUPABASE_SERVICE_KEY é obrigatória quando SUPABASE_URL está definida");
        }
        // Só esquema e host: aceita a URL do projeto (https://<ref>.supabase.co) e também a do endpoint S3
        // (.../storage/v1/s3) ou qualquer outro caminho colado por engano, que levaria a API REST a responder
        // como S3 ("Missing signature").
        URI uri = URI.create(projectUrl.trim());
        if (uri.getHost() == null) {
            throw new IllegalStateException("SUPABASE_URL inválida: " + projectUrl + " (esperado https://<ref>.supabase.co)");
        }
        this.base = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + "/storage/v1";
        this.key = key;
        this.bucket = bucket == null || bucket.isBlank() ? "receipts" : bucket;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public void put(String name, byte[] bytes) throws IOException {
        HttpResponse<String> r = send(upload(name, bytes));
        if (r.statusCode() == 404 && r.body().contains("Bucket not found")) {
            createBucket();
            r = send(upload(name, bytes));
        }
        if (r.statusCode() >= 300) {
            throw new IOException("Supabase Storage respondeu " + r.statusCode() + " ao gravar " + name + ": " + r.body());
        }
    }

    @Override
    public Optional<byte[]> get(String name) throws IOException {
        HttpRequest req = request("/object/authenticated/" + bucket + "/" + name).GET().build();
        HttpResponse<byte[]> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Leitura interrompida", e);
        }
        if (r.statusCode() == 200) {
            return Optional.of(r.body());
        }
        if (r.statusCode() == 404 || r.statusCode() == 400) {
            return Optional.empty();
        }
        throw new IOException("Supabase Storage respondeu " + r.statusCode() + " ao ler " + name);
    }

    private HttpRequest upload(String name, byte[] bytes) {
        return request("/object/" + bucket + "/" + name)
            .header("Content-Type", "image/jpeg")
            .header("x-upsert", "false")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build();
    }

    private void createBucket() throws IOException {
        String body = "{\"id\":\"" + bucket + "\",\"name\":\"" + bucket + "\",\"public\":false}";
        HttpResponse<String> r = send(request("/bucket")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build());
        if (r.statusCode() >= 300 && r.statusCode() != 409) {
            throw new IOException("Não foi possível criar o bucket " + bucket + " (" + r.statusCode() + "): " + r.body());
        }
        log.info("Bucket {} criado no Supabase Storage", bucket);
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(30))
            .header("apikey", key);
        // Chave legada (service_role) é um JWT e vai também no Authorization. As chaves novas (sb_secret_...) não
        // são JWT: o Storage responde "Invalid Compact JWS" se forem mandadas ali; só o apikey as autentica.
        if (looksLikeJwt(key)) {
            b.header("Authorization", "Bearer " + key);
        }
        return b;
    }

    static boolean looksLikeJwt(String key) {
        return key.chars().filter(c -> c == '.').count() == 2 && !key.startsWith("sb_");
    }

    private HttpResponse<String> send(HttpRequest req) throws IOException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Gravação interrompida", e);
        }
    }

    @Override
    public String toString() {
        return "Supabase Storage " + base + " (bucket " + bucket + ")";
    }
}
