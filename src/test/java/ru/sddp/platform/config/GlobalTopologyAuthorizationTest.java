package ru.sddp.platform.config;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import ru.sddp.platform.service.TopologyWriteService;
import ru.sddp.platform.web.GlobalTopologyController;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalTopologyAuthorizationTest {
    private static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String ROUTE = "/api/v1/topology/connections";
    private static final String REQUEST = """
        {
          "projectId": "%s",
          "connection": {
            "environment": "DEV",
            "sourceServiceId": "22222222-2222-2222-2222-222222222222",
            "targetServiceId": "33333333-3333-3333-3333-333333333333",
            "targetApiId": "44444444-4444-4444-4444-444444444444",
            "transportType": "REST",
            "connectionSettings": {}
          }
        }
        """.formatted(PROJECT);

    @Test
    void oidcChecksProjectBeforeWriting() throws Exception {
        try (var context = app(true)) {
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
            var topology = context.getBean(TopologyWriteService.class);

            mvc.perform(post(ROUTE).contentType("application/json").content(REQUEST))
                .andExpect(status().isUnauthorized());
            mvc.perform(post(ROUTE).header("Authorization", "Bearer unrelated")
                    .contentType("application/json").content(REQUEST))
                .andExpect(status().isForbidden());
            verifyNoInteractions(topology);

            mvc.perform(post(ROUTE).header("Authorization", "Bearer member")
                    .contentType("application/json").content(REQUEST))
                .andExpect(status().isCreated());
            mvc.perform(post(ROUTE).header("Authorization", "Bearer admin")
                    .contentType("application/json").content(REQUEST))
                .andExpect(status().isCreated());
            verify(topology, times(2)).addConnection(eq(PROJECT), any(), eq(false));
        }
    }

    @Test
    void localConfigurationStillAcceptsUnauthenticatedRequests() throws Exception {
        try (var context = app(false)) {
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();

            mvc.perform(post(ROUTE).contentType("application/json").content(REQUEST))
                .andExpect(status().isCreated());
            verify(context.getBean(TopologyWriteService.class)).addConnection(eq(PROJECT), any(), eq(false));
        }
    }

    private AnnotationConfigWebApplicationContext app(boolean oidcEnabled) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("test", Map.of("sddp.security.oidc.enabled", oidcEnabled)));
        context.register(TestWebConfig.class, GlobalTopologyController.class,
            OidcSecurityConfiguration.class, LocalSecurityConfiguration.class);
        context.refresh();
        return context;
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class TestWebConfig {
        @Bean
        TopologyWriteService topology() {
            return mock(TopologyWriteService.class);
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return tokenValue -> Jwt.withTokenValue(tokenValue).header("alg", "RS256")
                .subject(tokenValue)
                .claim("roles", tokenValue.equals("admin") ? List.of("SDDP_ADMIN") : List.of())
                .claim("projects", tokenValue.equals("member") ? List.of(PROJECT.toString()) : List.of())
                .build();
        }
    }
}
