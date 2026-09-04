package com.distributedai.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class PrometheusMetricAliasFilter extends OncePerRequestFilter {

    private static final String PROMETHEUS_PATH = "/actuator/prometheus";
    private static final String OPEN_METRICS_EOF = "# EOF";

    private final BusinessMetrics businessMetrics;

    public PrometheusMetricAliasFilter(BusinessMetrics businessMetrics) {
        this.businessMetrics = businessMetrics;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        if (!PROMETHEUS_PATH.equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, wrapper);

        String alias = businessMetrics.jobsCreatedPrometheusAlias();
        if (alias.isBlank() || wrapper.getStatus() != HttpServletResponse.SC_OK) {
            wrapper.copyBodyToResponse();
            return;
        }

        String body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
        if (body.contains("jobs_created_total")) {
            wrapper.copyBodyToResponse();
            return;
        }

        wrapper.resetBuffer();
        wrapper.getOutputStream().write(withAlias(body, alias).getBytes(StandardCharsets.UTF_8));
        wrapper.copyBodyToResponse();
    }

    private String withAlias(String body, String alias) {
        int eofIndex = body.lastIndexOf(OPEN_METRICS_EOF);
        if (eofIndex < 0) {
            return body + "\n" + alias;
        }

        return body.substring(0, eofIndex) + alias + body.substring(eofIndex);
    }
}
