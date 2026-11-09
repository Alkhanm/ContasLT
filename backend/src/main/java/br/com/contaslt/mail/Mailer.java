package br.com.contaslt.mail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import br.com.contaslt.config.AppProperties;
import tools.jackson.databind.ObjectMapper;

/**
 * Envia e-mails transacionais pela API HTTP do Brevo (o Railway bloqueia SMTP de saída nos planos menores). Sem
 * {@code BREVO_API_KEY} configurada, só registra no log que o e-mail seria enviado (desenvolvimento).
 */
@Component
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);
    private static final URI BREVO = URI.create("https://api.brevo.com/v3/smtp/email");

    private final AppProperties.Mail cfg;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public Mailer(AppProperties props, ObjectMapper json) {
        this.cfg = props.mail();
        this.json = json;
    }

    public boolean enabled() {
        return cfg != null && cfg.enabled();
    }

    /** Envia (bloqueante). Falhas viram exceção para quem chamou registrar; o conteúdo nunca vai para o log. */
    public void send(String toEmail, String toName, String subject, String text, String html) {
        Map<String, Object> body = Map.of(
            "sender", Map.of("name", cfg.fromName() == null ? "ContasLT" : cfg.fromName(), "email", cfg.from()),
            "to", List.of(Map.of("email", toEmail, "name", toName == null || toName.isBlank() ? toEmail : toName)),
            "subject", subject,
            "textContent", text,
            "htmlContent", html);
        HttpRequest req = HttpRequest.newBuilder(BREVO)
            .timeout(Duration.ofSeconds(20))
            .header("api-key", cfg.brevoApiKey())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("Brevo respondeu " + res.statusCode() + ": " + res.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Envio de e-mail interrompido", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Falha ao falar com o Brevo", e);
        }
        log.info("E-mail \"{}\" enviado", subject);
    }
}
