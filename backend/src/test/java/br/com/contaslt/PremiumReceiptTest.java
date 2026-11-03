package br.com.contaslt;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import br.com.contaslt.user.User;
import br.com.contaslt.user.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Só usuários premium (coluna no banco) mandam comprovantes para leitura; os demais recebem 403. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "app.receipts.provider=worker", "app.premium.enforce=true" })
class PremiumReceiptTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    UserRepository users;

    @Test
    void onlyPremiumUsersCanSendReceipts() throws Exception {
        String email = "premium-" + System.nanoTime() + "@contaslt.app";
        MvcTestResult reg = mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "P", "email", email, "password", "segredo123"))).exchange();
        JsonNode body = json.readTree(reg.getResponse().getContentAsByteArray());
        String token = body.get("token").asString();
        assertThat(body.get("user").get("premium").asBoolean()).isFalse();   // cadastro novo nunca é premium

        BufferedImage img = new BufferedImage(40, 60, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", out);
        MockMultipartFile photo = new MockMultipartFile("file", "cupom.jpg", "image/jpeg", out.toByteArray());

        MvcTestResult denied = mvc.post().uri("/api/receipts/scan").headers(auth(token)).multipart().file(photo).exchange();
        assertThat(denied).hasStatus(403);
        assertThat(denied.getResponse().getContentAsString()).contains("não está liberada");

        User user = users.findByEmailIgnoreCase(email).orElseThrow();
        user.setPremium(true);
        users.save(user);
        assertThat(json.readTree(mvc.get().uri("/api/auth/me").headers(auth(token)).exchange().getResponse().getContentAsByteArray())
            .get("premium").asBoolean()).isTrue();
        assertThat(mvc.post().uri("/api/receipts/scan").headers(auth(token)).multipart().file(photo).exchange()).hasStatusOk();
    }

    private static HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }
}
