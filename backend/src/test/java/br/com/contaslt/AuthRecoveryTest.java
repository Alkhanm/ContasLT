package br.com.contaslt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import br.com.contaslt.auth.GoogleTokenVerifier;
import br.com.contaslt.auth.GoogleTokenVerifier.GoogleIdentity;
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.mail.Mailer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Recuperação de senha por código de 6 dígitos e login com Google (criação, vínculo por e-mail, exclusão). */
@SpringBootTest
@AutoConfigureMockMvc
class AuthRecoveryTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    Mailer mailer;

    @MockitoBean
    GoogleTokenVerifier google;

    @BeforeEach
    void setUp() {
        when(mailer.enabled()).thenReturn(true);
    }

    private MvcTestResult post(String uri, Map<String, ?> body) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)).exchange();
    }

    private JsonNode body(MvcTestResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String register(String email) {
        assertThat(post("/api/auth/register", Map.of("name", "Ana Souza", "email", email, "password", "antiga123"))).hasStatus(201);
        return email;
    }

    /** Pede um código e devolve o que foi "enviado" no assunto do e-mail. */
    private String requestCode(String email) {
        Mockito.clearInvocations(mailer);
        assertThat(post("/api/auth/forgot-password", Map.of("email", email))).hasStatus(204);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(mailer, timeout(3000)).send(eq(email), anyString(), subject.capture(), anyString(), anyString());
        return subject.getValue().replaceAll("\\D", "");
    }

    @Test
    void resetsPasswordWithTheEmailedCode() throws Exception {
        String email = register("reset-" + System.nanoTime() + "@exemplo.com");
        String code = requestCode(email);
        assertThat(code).hasSize(6);

        String wrong = code.equals("000000") ? "111111" : "000000";
        assertThat(post("/api/auth/reset-password", Map.of("email", email, "code", wrong, "password", "nova12345"))).hasStatus(422);

        MvcTestResult ok = post("/api/auth/reset-password", Map.of("email", email.toUpperCase(), "code", code, "password", "nova12345"));
        assertThat(ok).hasStatusOk();
        assertThat(body(ok).get("token").asString()).isNotBlank();

        assertThat(post("/api/auth/login", Map.of("email", email, "password", "antiga123"))).hasStatus(401);
        assertThat(post("/api/auth/login", Map.of("email", email, "password", "nova12345"))).hasStatusOk();
        // Uso único.
        assertThat(post("/api/auth/reset-password", Map.of("email", email, "code", code, "password", "outra12345"))).hasStatus(422);
    }

    @Test
    void unknownEmailLooksTheSameAndSendsNothing() {
        assertThat(post("/api/auth/forgot-password", Map.of("email", "ninguem-" + System.nanoTime() + "@exemplo.com"))).hasStatus(204);
        verify(mailer, Mockito.after(300).never()).send(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void tooManyWrongAttemptsBurnTheCodeAndANewCodeReplacesTheOld() {
        String email = register("tentativas-" + System.nanoTime() + "@exemplo.com");
        String first = requestCode(email);
        String second = requestCode(email);
        // O código antigo deixa de valer quando sai um novo.
        if (!first.equals(second)) {
            assertThat(post("/api/auth/reset-password", Map.of("email", email, "code", first, "password", "nova12345"))).hasStatus(422);
        }
        String wrong = second.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            post("/api/auth/reset-password", Map.of("email", email, "code", wrong, "password", "nova12345"));
        }
        // Mesmo o código certo não vale mais depois de 5 erros.
        assertThat(post("/api/auth/reset-password", Map.of("email", email, "code", second, "password", "nova12345"))).hasStatus(422);
    }

    @Test
    void limitsCodesPerWindow() {
        String email = register("limite-" + System.nanoTime() + "@exemplo.com");
        for (int i = 0; i < 3; i++) {
            requestCode(email);
        }
        Mockito.clearInvocations(mailer);
        assertThat(post("/api/auth/forgot-password", Map.of("email", email))).hasStatus(204);
        verify(mailer, Mockito.after(300).never()).send(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void googleCreatesAccountThenLinksByEmailAndDeletes() throws Exception {
        // Conta nova pelo Google: sem senha.
        String newEmail = "google-" + System.nanoTime() + "@gmail.com";
        when(google.verify("tok-new")).thenReturn(new GoogleIdentity("sub-new-" + newEmail, newEmail, true, "Bia Lima"));
        MvcTestResult created = post("/api/auth/google", Map.of("idToken", "tok-new"));
        assertThat(created).hasStatusOk();
        assertThat(body(created).get("created").asBoolean()).isTrue();
        assertThat(body(created).get("user").get("name").asString()).isEqualTo("Bia Lima");
        long newId = body(created).get("user").get("id").asLong();
        MvcTestResult again = post("/api/auth/google", Map.of("idToken", "tok-new"));
        assertThat(body(again).get("created").asBoolean()).isFalse();
        assertThat(body(again).get("user").get("id").asLong()).isEqualTo(newId);
        // Sem senha, login por senha nunca confere.
        assertThat(post("/api/auth/login", Map.of("email", newEmail, "password", ""))).hasStatus(400);

        // Conta existente com o mesmo e-mail: vincula e entra na mesma conta.
        String email = register("vinculo-" + System.nanoTime() + "@exemplo.com");
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        when(google.verify("tok-link")).thenReturn(new GoogleIdentity("sub-link-" + email, email.toUpperCase(), true, "Outro Nome"));
        MvcTestResult linked = post("/api/auth/google", Map.of("idToken", "tok-link"));
        assertThat(linked).hasStatusOk();
        assertThat(body(linked).get("user").get("id").asLong()).isEqualTo(userId);
        assertThat(body(linked).get("created").asBoolean()).isFalse();
        assertThat(post("/api/auth/login", Map.of("email", email, "password", "antiga123"))).hasStatusOk();

        // E-mail não confirmado pelo Google: não vincula.
        String other = register("naoverificado-" + System.nanoTime() + "@exemplo.com");
        when(google.verify("tok-unverified")).thenReturn(new GoogleIdentity("sub-x-" + other, other, false, "X"));
        assertThat(post("/api/auth/google", Map.of("idToken", "tok-unverified"))).hasStatus(422);

        // Token inválido.
        when(google.verify("tok-bad")).thenThrow(new BusinessException("Não foi possível confirmar o login com o Google. Tente de novo."));
        assertThat(post("/api/auth/google", Map.of("idToken", "tok-bad"))).hasStatus(422);

        // Exclusão confirmada pelo Google (conta sem senha).
        assertThat(post("/api/auth/delete-account", Map.of("googleIdToken", "tok-new"))).hasStatus(204);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, newId)).isZero();
        verify(mailer, never()).send(eq(newEmail), any(), anyString(), anyString(), anyString());
    }
}
