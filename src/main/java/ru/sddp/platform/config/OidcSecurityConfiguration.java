package ru.sddp.platform.config;

import java.util.ArrayList;
import java.util.Collection;
import org.springframework.http.HttpMethod;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** Optional JWT/OIDC protection. It activates only when an issuer is configured. */
@Configuration
@ConditionalOnProperty(name = "sddp.security.oidc.enabled", havingValue = "true")
public class OidcSecurityConfiguration {
    private final ProjectAccessPolicy projectAccess = new ProjectAccessPolicy();

    @Bean
    SecurityFilterChain oidcSecurityFilterChain(HttpSecurity http) throws Exception {
        AuthorizationManager<RequestAuthorizationContext> projectAuthorization = (authentication, context) ->
            new AuthorizationDecision(projectAccess.isAllowed(authentication.get(), context.getRequest().getRequestURI()));

        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/**/*.html", "/**/*.js", "/**/*.css", "/favicon.svg").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/actuator/metrics/**").hasRole("SDDP_ADMIN")
                .requestMatchers("/api/v1/projects", "/api/v1/projects/import").hasRole("SDDP_ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/codegen/generate").hasRole("SDDP_ADMIN")
                .requestMatchers("/api/v1/projects/*").access(projectAuthorization)
                .requestMatchers("/api/v1/projects/*/**").access(projectAuthorization)
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    private Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        return jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
            Object roles = jwt.getClaims().get("roles");
            if (roles instanceof Collection<?> values) {
                values.forEach(value -> authorities.add(new SimpleGrantedAuthority("ROLE_" + value)));
            } else if (roles instanceof String value) {
                for (String role : value.split("[, ]+")) if (!role.isBlank()) authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
            JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
            converter.setJwtGrantedAuthoritiesConverter(ignored -> authorities);
            return converter.convert(jwt);
        };
    }
}
