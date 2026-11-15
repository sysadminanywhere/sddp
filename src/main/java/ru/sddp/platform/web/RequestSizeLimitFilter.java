package ru.sddp.platform.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;

/** Limits API bodies, including chunked requests without a Content-Length header. */
@Component
public class RequestSizeLimitFilter extends OncePerRequestFilter {
    private final int maxBytes;
    public RequestSizeLimitFilter(@Value("${sddp.max-request-bytes:${SDDP_MAX_REQUEST_BYTES:10000000}}") int maxBytes) {
        if (maxBytes < 1 || maxBytes == Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid sddp.max-request-bytes");
        this.maxBytes = maxBytes;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || request.getMethod().equals("GET") || request.getMethod().equals("HEAD") || request.getMethod().equals("OPTIONS");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxBytes) { reject(response); return; }
        // A known length within the limit is enforced by the container, so the body can stream through.
        if (declaredLength >= 0) { chain.doFilter(request, response); return; }
        byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) { reject(response); return; }
        chain.doFilter(new BufferedRequest(request, body), response);
    }
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":413,\"detail\":\"Request body exceeds configured size limit\"}");
    }
    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final ServletInputStream stream;
        BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            var input = new ByteArrayInputStream(body);
            stream = new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Async input is not supported"); }
            };
        }
        @Override public ServletInputStream getInputStream() { return stream; }
        @Override public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(stream, charset));
        }
    }
}
