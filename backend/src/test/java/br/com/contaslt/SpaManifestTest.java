package br.com.contaslt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** O tipo MIME do manifesto vem do Tomcat real, por isso este teste sobe o servidor de verdade. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpaManifestTest {

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void spaDir(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("contaslt-spa");
        Files.writeString(dir.resolve("index.html"), "<!doctype html><title>ContasLT</title>");
        Files.writeString(dir.resolve("manifest.webmanifest"), "{\"name\":\"ContasLT\"}");
        registry.add("app.spa.dir", dir::toString);
    }

    @Test
    void servesManifestWithPwaMediaType() throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/manifest.webmanifest")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("application/manifest+json"));
        assertThat(res.body()).contains("ContasLT");
    }
}
