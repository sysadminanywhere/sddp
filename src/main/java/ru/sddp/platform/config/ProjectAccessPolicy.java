package ru.sddp.platform.config;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/** Project-level authorization policy used by the OIDC request matchers. */
public class ProjectAccessPolicy {
    public boolean isAllowed(Authentication authentication, String requestUri) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        if (authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SDDP_ADMIN"))) return true;
        String[] parts = requestUri.split("/");
        if (parts.length < 5) return false;
        String projectId = parts[4];
        if (!(authentication instanceof JwtAuthenticationToken token)) return false;
        Jwt jwt = token.getToken();
        Object projects = jwt.getClaims().get("projects");
        if (projects instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).anyMatch(projectId::equals);
        }
        if (projects instanceof String value) {
            return List.of(value.split(",")).stream().map(String::trim).anyMatch(projectId::equals);
        }
        return false;
    }
}
