package br.com.contaslt.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serve o build do frontend (pasta dist do Vite) a partir do backend, em {@code app.spa.dir}.
 * Rotas do Vue Router (ex.: /lancamentos) caem no index.html; arquivos inexistentes com extensão dão 404.
 * Sem a propriedade, nada é registrado e o frontend fica por conta do servidor de desenvolvimento.
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SpaConfig.class);

    /** Pasta do build, ou null quando o backend não serve o frontend. */
    private final Path root;

    public SpaConfig(AppProperties props) {
        this.root = resolveRoot(props.spa() == null ? null : props.spa().dir());
    }

    private static Path resolveRoot(String dir) {
        if (dir == null || dir.isBlank()) {
            return null;
        }
        Path root = Path.of(dir).toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("index.html"))) {
            log.warn("app.spa.dir={} não contém index.html; o frontend não será servido", root);
            return null;
        }
        log.info("Servindo o frontend de {}", root);
        return root;
    }

    /** O manifesto da PWA precisa do tipo certo para o navegador oferecer a instalação (Tomcat não conhece .webmanifest). */
    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> webmanifestMimeType() {
        return factory -> {
            MimeMappings mappings = new MimeMappings(MimeMappings.DEFAULT);
            mappings.add("webmanifest", "application/manifest+json");
            factory.setMimeMappings(mappings);
        };
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (root == null) {
            return;
        }
        // O handler de recursos ignora o caminho vazio; a raiz precisa ser encaminhada explicitamente.
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (root == null) {
            return;
        }
        String location = root.toUri().toString();

        // Arquivos com hash no nome (assets/) podem ficar em cache por muito tempo.
        registry.addResourceHandler("/assets/**")
            .addResourceLocations(location + "assets/")
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).immutable());

        // Todo o resto: arquivo existente, ou index.html para rotas da aplicação.
        registry.addResourceHandler("/**")
            .addResourceLocations(location)
            .setCacheControl(CacheControl.noCache())
            .resourceChain(false)
            .addResolver(new SpaFallbackResolver());
    }

    private static final class SpaFallbackResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable()) {
                return requested;
            }
            // /api e /actuator nunca viram index.html; nem caminhos que parecem arquivos (favicon.png, sw.js...).
            if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/") || looksLikeFile(resourcePath)) {
                return null;
            }
            return location.createRelative("index.html");
        }

        private static boolean looksLikeFile(String path) {
            int slash = path.lastIndexOf('/');
            return path.indexOf('.', slash + 1) >= 0;
        }
    }
}
