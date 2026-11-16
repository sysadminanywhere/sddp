package ru.sddp.platform.config;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectAccessPolicyTest {
    private static final String PROJECT = "11111111-1111-1111-1111-111111111111";
    private final ProjectAccessPolicy policy = new ProjectAccessPolicy();

    @Test
    void adminCanAccessAnyProject() {
        var token = token(List.of(), List.of(new SimpleGrantedAuthority("ROLE_SDDP_ADMIN")));
        assertTrue(policy.isAllowed(token, "/api/v1/projects/22222222-2222-2222-2222-222222222222/topology"));
    }

    @Test
    void userCanAccessListedProjectOnly() {
        var token = token(List.of(PROJECT), List.of());
        assertTrue(policy.isAllowed(token, "/api/v1/projects/" + PROJECT));
        assertFalse(policy.isAllowed(token, "/api/v1/projects/22222222-2222-2222-2222-222222222222"));
    }

    @Test
    void missingProjectClaimIsDenied() {
        var token = token(List.of(), List.of());
        assertFalse(policy.isAllowed(token, "/api/v1/projects/" + PROJECT + "/services"));
    }

    private JwtAuthenticationToken token(List<String> projects, List<SimpleGrantedAuthority> authorities) {
        var jwt = Jwt.withTokenValue("test-token").header("alg", "none")
            .claim("sub", "user").claim("projects", projects)
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
