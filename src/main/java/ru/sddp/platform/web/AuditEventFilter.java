package ru.sddp.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.sddp.platform.domain.AuditEvent;
import ru.sddp.platform.repository.AuditEventRepository;

/** Records state-changing API requests without persisting credentials or request bodies. */
@Component
public class AuditEventFilter extends OncePerRequestFilter {
    private final AuditEventRepository events;

    public AuditEventFilter(AuditEventRepository events) { this.events = events; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || switch (request.getMethod()) {
            case "POST", "PUT", "PATCH", "DELETE" -> false;
            default -> true;
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            Principal principal = request.getUserPrincipal();
            String actor = principal == null ? "anonymous" : principal.getName();
            String path = request.getRequestURI().substring(request.getContextPath().length());
            events.save(new AuditEvent(actor, request.getMethod(), path, response.getStatus(),
                    (System.nanoTime() - started) / 1_000_000));
        }
    }
}
