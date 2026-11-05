package br.com.contaslt;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exclusão de conta (Play Store): confirma por e-mail e senha e apaga usuário, dados sincronizados e fotos. */
@SpringBootTest
@AutoConfigureMockMvc
class DeleteAccountTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private JsonNode body(MvcTestResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void deletesEverythingOnlyWithTheRightPassword() throws Exception {
        String email = "apagar-" + System.nanoTime() + "@exemplo.com";
        JsonNode reg = body(mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Apagar", "email", email, "password", "segredo123"))).exchange());
        long userId = reg.get("user").get("id").asLong();
        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth(reg.get("token").asString());

        UUID id = UUID.randomUUID();
        mvc.post().uri("/api/sync/push").headers(auth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("changes", List.of(Map.of("id", id.toString(), "entity", "account",
                "data", Map.of("id", id.toString(), "name", "Conta"), "deleted", false, "updatedAt", 1))))).exchange();
        BufferedImage img = new BufferedImage(40, 60, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", out);
        String file = body(mvc.post().uri("/api/receipts").headers(auth).multipart()
            .file(new MockMultipartFile("file", "f.jpg", "image/jpeg", out.toByteArray())).exchange()).get("receiptFile").asString();
        assertThat(mvc.get().uri("/api/receipts/" + file).headers(auth).exchange()).hasStatusOk();

        MvcTestResult wrong = mvc.post().uri("/api/auth/delete-account").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", email, "password", "errada"))).exchange();
        assertThat(wrong).hasStatus(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId)).isEqualTo(1);

        MvcTestResult ok = mvc.post().uri("/api/auth/delete-account").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", email.toUpperCase(), "password", "segredo123"))).exchange();
        assertThat(ok).hasStatus(204);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sync_records WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipt_jobs WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(mvc.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", email, "password", "segredo123"))).exchange()).hasStatus(401);
        java.nio.file.Path stored = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "contaslt-test-storage", "receipts", file);
        assertThat(java.nio.file.Files.exists(stored)).isFalse();
    }
}
