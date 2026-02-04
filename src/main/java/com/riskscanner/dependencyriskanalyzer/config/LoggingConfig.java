package com.riskscanner.dependencyriskanalyzer.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * Configuration for structured logging with correlation IDs.
 */
@Configuration
public class LoggingConfig {

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> correlationIdFilter() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new CorrelationIdFilter());
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }

    /**
     * Filter that adds correlation ID to MDC for request tracking.
     */
    public static class CorrelationIdFilter extends OncePerRequestFilter {
        
        private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
        private static final String CORRELATION_ID_MDC_KEY = "correlationId";

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            
            String correlationId = request.getHeader(CORRELATION_ID_HEADER);
            if (correlationId == null || correlationId.trim().isEmpty()) {
                correlationId = generateCorrelationId();
            }
            
            // Add to MDC for logging
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
            
            // Add to response header
            response.setHeader(CORRELATION_ID_HEADER, correlationId);
            
            try {
                filterChain.doFilter(request, response);
            } finally {
                // Clean up MDC
                MDC.remove(CORRELATION_ID_MDC_KEY);
            }
        }

        private String generateCorrelationId() {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
    }

    /**
     * Utility class for correlation ID management.
     */
    public static class CorrelationIdUtils {
        
        private static final String CORRELATION_ID_MDC_KEY = "correlationId";

        public static String getCurrentCorrelationId() {
            return MDC.get(CORRELATION_ID_MDC_KEY);
        }

        public static void setCorrelationId(String correlationId) {
            if (correlationId != null) {
                MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
            }
        }

        public static void clearCorrelationId() {
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }

        public static String generateNewCorrelationId() {
            String correlationId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            setCorrelationId(correlationId);
            return correlationId;
        }
    }
}
