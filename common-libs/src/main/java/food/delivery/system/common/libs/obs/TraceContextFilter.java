package food.delivery.system.common.libs.obs;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Copies the X-User-Id request header into MDC so every log line within a request
 * automatically includes the userId field without any per-method instrumentation.
 *
 * Downstream services set X-User-Id after validating the JWT (in user-service) or
 * via API Gateway's JWT authorizer. This filter trusts that header value.
 */
public class TraceContextFilter extends OncePerRequestFilter {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String MDC_USER_ID    = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String userId = request.getHeader(USER_ID_HEADER);
        if (userId != null && !userId.isBlank()) {
            MDC.put(MDC_USER_ID, userId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_USER_ID);
        }
    }
}
