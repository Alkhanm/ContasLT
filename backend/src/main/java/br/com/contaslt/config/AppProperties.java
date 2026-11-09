package br.com.contaslt.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Cors cors, Receipts receipts, Ollama ollama, OpenAi openai, Storage storage, Spa spa,
        Worker worker, Premium premium, Mail mail, Google google) {

    public record Jwt(String secret, Duration expiration) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    /**
     * Quem lê os comprovantes: Ollama local, uma API compatível com OpenAI, o worker do computador do usuário
     * (fila: a foto espera até ele estar ligado), ou ninguém (recurso desligado).
     */
    public record Receipts(Provider provider) {
        public enum Provider { OLLAMA, OPENAI, WORKER, NONE }

        public Provider providerOrDefault() {
            return provider == null ? Provider.OLLAMA : provider;
        }
    }

    /** Recursos premium (leitura de comprovantes, backup) só para {@code users.premium}. Padrão: exigir. */
    public record Premium(Boolean enforce) {
        public boolean enforceOrDefault() {
            return enforce == null || enforce;
        }
    }

    /** Modelo de visão local servido pelo Ollama. */
    public record Ollama(String url, String model, Duration timeout) {
    }

    /** API de chat compatível com OpenAI (OpenAI, Gemini, Groq, OpenRouter...), usada em produção. */
    public record OpenAi(String url, String apiKey, String model, Duration timeout) {
    }

    /** Onde ficam as imagens dos comprovantes: pasta local, ou um bucket do Supabase Storage quando {@code supabase.url} vem preenchido. */
    public record Storage(String dir, Supabase supabase) {
        public record Supabase(String url, String key, String bucket) {
        }

        public boolean usesSupabase() {
            return supabase != null && supabase.url() != null && !supabase.url().isBlank();
        }
    }

    /** Pasta com o build do frontend (dist). Vazia = o backend não serve o frontend. */
    public record Spa(String dir) {
    }

    /** Segredo que o worker apresenta em {@code X-Worker-Token} nas rotas {@code /api/worker/**}. Vazio = rotas fechadas. */
    public record Worker(String token) {
    }

    /** Envio de e-mail (código de recuperação de senha) pela API HTTP do Brevo. Sem chave: o código só vai para o log. */
    public record Mail(String brevoApiKey, String from, String fromName) {
        public boolean enabled() {
            return brevoApiKey != null && !brevoApiKey.isBlank();
        }
    }

    /** IDs de cliente OAuth aceitos como {@code aud} do ID token do Google (web; o Android usa o mesmo id web). */
    public record Google(List<String> clientIds) {
        public List<String> ids() {
            return clientIds == null ? List.of() : clientIds.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }
}
