package br.com.contaslt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Com app.spa.dir apontando para um build, o backend serve o frontend e devolve index.html para rotas do Vue Router. */
@SpringBootTest
@AutoConfigureMockMvc
class SpaServingTest {

    @Autowired
    MockMvcTester mvc;

    @DynamicPropertySource
    static void spaDir(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("contaslt-spa");
        Files.writeString(dir.resolve("index.html"), "<!doctype html><title>ContasLT</title>");
        Files.createDirectories(dir.resolve("assets"));
        Files.writeString(dir.resolve("assets/app-abc123.js"), "console.log('ok')");
        Files.writeString(dir.resolve("manifest.webmanifest"), "{\"name\":\"ContasLT\"}");
        registry.add("app.spa.dir", dir::toString);
    }

    @Test
    void servesIndexAndFallsBackForAppRoutes() {
        // O MockMvc não executa forwards: a raiz é encaminhada para o index.html.
        assertThat(mvc.get().uri("/")).hasStatusOk().hasForwardedUrl("/index.html");
        assertThat(mvc.get().uri("/index.html")).hasStatusOk().bodyText().contains("ContasLT");
        assertThat(mvc.get().uri("/lancamentos")).hasStatusOk().bodyText().contains("ContasLT");
        assertThat(mvc.get().uri("/contas/12")).hasStatusOk().bodyText().contains("ContasLT");
    }

    @Test
    void servesAssetsWithLongCache() {
        assertThat(mvc.get().uri("/assets/app-abc123.js")).hasStatusOk()
            .hasHeader("Cache-Control", "max-age=31536000, immutable")
            .bodyText().contains("ok");
    }

    @Test
    void missingFilesAndApiStayOutOfTheFallback() {
        assertThat(mvc.get().uri("/nao-existe.png")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/api/nao-existe")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/accounts")).hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
