package br.com.contaslt.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Protege {@code /api/worker/**} com o segredo do worker ({@code X-Worker-Token}, de {@code app.worker.token}).
 * Sem segredo configurado, as rotas ficam fechadas. Comparação em tempo constante.
 */
public class WorkerAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Worker-Token";

    private final AppProperties props;

    public WorkerAuthFilter(AppProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/worker/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String expected = props.worker() == null ? null : props.worker().token();
        String given = request.getHeader(HEADER);
        boolean ok = expected != null && !expected.isBlank() && given != null
            && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/problem+json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"status\":401,\"title\":\"Unauthorized\",\"detail\":\"Token do worker inválido\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
