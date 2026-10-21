package br.com.contaslt.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Cors cors, Ollama ollama, Storage storage) {

    public record Jwt(String secret, Duration expiration) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    /** Leitura de comprovantes por um modelo de visão local (Ollama). */
    public record Ollama(String url, String model, Duration timeout) {
    }

    /** Pasta onde ficam as imagens dos comprovantes. */
    public record Storage(String dir) {
    }
}
