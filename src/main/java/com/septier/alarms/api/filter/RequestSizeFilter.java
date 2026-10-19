package com.septier.alarms.api.filter;



import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;

/** Bound JSON bodies including chunked requests before Jackson allocates objects. */
@Component
class RequestSizeFilter extends OncePerRequestFilter {
    private static final int LIMIT = 16384;
    private final RequestAdmission admission;
    private final long uploadTimeoutNanos;
    public RequestSizeFilter(RequestAdmission admission,
            @org.springframework.beans.factory.annotation.Value("${alarm.upload-timeout-ms}") long timeout) {
        this.admission = admission; this.uploadTimeoutNanos = timeout * 1_000_000L;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        if (!admission.enter(request.getRemoteAddr())) {
            response.setHeader("Retry-After", "1"); reject(response, 429, "Request admission limit reached"); return;
        }
        try { bounded(request, response, chain); } finally { admission.exit(); }
    }
    private void bounded(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equals(request.getMethod())) { chain.doFilter(request, response); return; }
        long started = System.nanoTime();
        var bytes = new ByteArrayOutputStream();
        var buffer = new byte[1024];
        try {
            var input = request.getInputStream();
            while (bytes.size() <= LIMIT) {
                if (System.nanoTime() - started >= uploadTimeoutNanos) {
                    reject(response, 408, "Request upload deadline exceeded"); return;
                }
                int read = input.read(buffer, 0, Math.min(buffer.length, LIMIT + 1 - bytes.size()));
                if (System.nanoTime() - started >= uploadTimeoutNanos) {
                    reject(response, 408, "Request upload deadline exceeded"); return;
                }
                if (read == -1) break;
                bytes.write(buffer, 0, read);
            }
        } catch (IOException ex) {
            reject(response, 408, "Request upload interrupted or timed out"); return;
        }
        byte[] body = bytes.toByteArray();
        if (body.length > LIMIT) {
            response.setStatus(413);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"title\":\"Payload Too Large\",\"status\":413,\"detail\":\"JSON body must not exceed 16384 bytes\"}");
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    public int read() { return input.read(); }
                    public boolean isFinished() { return input.available() == 0; }
                    public boolean isReady() { return true; }
                    public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
            @Override public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }, response);
    }
    private void reject(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status); response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
