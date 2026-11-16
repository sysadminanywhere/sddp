package ru.sddp.platform.web;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import ru.sddp.platform.repository.AuditEventRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AuditEventFilterTest {
    private final AuditEventRepository repository = mock(AuditEventRepository.class);
    private final AuditEventFilter filter = new AuditEventFilter(repository);

    @Test
    void recordsStateChangingApiRequest() throws ServletException, IOException {
        var request = new MockHttpServletRequest("POST", "/api/v1/projects");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        verify(repository).save(any());
    }

    @Test
    void ignoresReadsAndStaticResources() throws ServletException, IOException {
        var get = new MockHttpServletRequest("GET", "/api/v1/projects");
        filter.doFilter(get, new MockHttpServletResponse(), new MockFilterChain());
        var staticResource = new MockHttpServletRequest("POST", "/index.html");
        filter.doFilter(staticResource, new MockHttpServletResponse(), new MockFilterChain());

        verify(repository, never()).save(any());
    }
}
