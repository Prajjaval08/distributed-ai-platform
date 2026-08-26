package com.distributedai.api.ratelimit;

import com.distributedai.api.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final int jobsPerHour;
    private final int authPerHour;

    public RateLimitFilter(
            RateLimiter rateLimiter,
            ObjectMapper objectMapper,
            @Value("${rate-limit.jobs-per-hour}") int jobsPerHour,
            @Value("${rate-limit.auth-per-hour}") int authPerHour
    ) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.jobsPerHour = jobsPerHour;
        this.authPerHour = authPerHour;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        RateLimitResult result = evaluate(request);

        if (result != null && !result.allowed()) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(result.remainingSeconds()));
            response.setContentType("application/json");
            ErrorResponse body = ErrorResponse.of("RATE_LIMITED",
                    "Too many requests. Try again in " + result.remainingSeconds() + " seconds.");
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private RateLimitResult evaluate(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();

        if ("POST".equals(method) && "/api/v1/jobs".equals(path)) {
            UUID userId = currentUserId();
            if (userId == null) {
                // Not authenticated - JwtAuthenticationFilter (which runs
                // earlier) or Spring Security's authorization rule will
                // reject this request anyway; don't rate-limit a request
                // that's about to be rejected for a different reason.
                return null;
            }
            return rateLimiter.checkAndIncrement("jobs", userId.toString(), jobsPerHour, Duration.ofHours(1));
        }

        if ("POST".equals(method) && ("/api/v1/auth/register".equals(path) || "/api/v1/auth/login".equals(path))) {
            String ip = clientIp(request);
            return rateLimiter.checkAndIncrement("auth", ip, authPerHour, Duration.ofHours(1));
        }

        return null; // not a rate-limited route
    }

    private UUID currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UUID userId)) {
            return null;
        }
        return userId;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Behind a proxy/load balancer (relevant from Phase 14 onward),
            // the first entry is the original client.
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}