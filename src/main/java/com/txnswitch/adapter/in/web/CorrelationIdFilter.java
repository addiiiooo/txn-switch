/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an identity that appears on the response, in every log line it produces, in
 * the audit trail, on the call to the acquirer, and in any problem document it returns.
 *
 * <p>Runs first, so that even an authentication failure is traceable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String correlationId = request.getHeader(HEADER);
    if (correlationId == null || correlationId.isBlank() || correlationId.length() > 64) {
      correlationId = UUID.randomUUID().toString();
    }
    MDC.put(MDC_KEY, correlationId);
    response.setHeader(HEADER, correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  /** The current request's correlation id, or a placeholder outside a request. */
  public static String current() {
    String correlationId = MDC.get(MDC_KEY);
    return correlationId == null ? "none" : correlationId;
  }
}
