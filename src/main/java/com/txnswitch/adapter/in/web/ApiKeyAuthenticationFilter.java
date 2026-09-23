/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.txnswitch.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves an API key to a merchant.
 *
 * <p>A header the caller sets is not a trust boundary, so there is no {@code X-Merchant-Id}: the
 * merchant identity comes from a credential and nowhere else. Keys are hashed once at startup and
 * looked up by hash, so there is no linear scan over the configured keys and no timing oracle.
 *
 * <p>What this is not: there is no rotation, no scope, no expiry and no revocation without a
 * restart. Real authentication belongs behind {@link MerchantPrincipal} — Spring Security with
 * OAuth2 client credentials, or mTLS with the certificate subject as the principal — and would
 * replace this class and nothing else. Spring Security is outside the agreed dependency set, so
 * this is a filter.
 *
 * <p>{@code /actuator/**} and {@code /__simulator/**} are deliberately unauthenticated and are
 * expected to be protected at the network level.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  static final String PRINCIPAL_ATTRIBUTE = "txnswitch.principal";

  private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
  private static final String BEARER = "Bearer ";

  private final Map<String, String> merchantByKeyHash;
  private final ProblemFactory problems;
  private final ObjectMapper objectMapper;

  public ApiKeyAuthenticationFilter(
      SecurityProperties properties,
      Environment environment,
      ProblemFactory problems,
      ObjectMapper objectMapper) {
    this.merchantByKeyHash = parse(properties.apiKeys());
    this.problems = problems;
    this.objectMapper = objectMapper;
    if (merchantByKeyHash.containsKey(sha256(SecurityProperties.DEVELOPMENT_API_KEY))
        && !environment.matchesProfiles("local", "test")) {
      log.warn(
          "The published development API key is configured. Replace API_KEYS before this "
              + "instance is reachable by anyone else.");
    }
  }

  private static Map<String, String> parse(String configured) {
    Map<String, String> byHash = new HashMap<>();
    if (configured == null || configured.isBlank()) {
      return byHash;
    }
    for (String pair : configured.split(",")) {
      String[] parts = pair.trim().split(":", 2);
      if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
        byHash.put(sha256(parts[0].trim()), parts[1].trim());
      }
    }
    return byHash;
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER)) {
      reject(response, ErrorCode.MISSING_CREDENTIALS, "Supply an API key as a bearer token.");
      return;
    }
    String merchantId = merchantByKeyHash.get(sha256(header.substring(BEARER.length()).trim()));
    if (merchantId == null) {
      // Says nothing about which keys exist or how close this one was.
      reject(response, ErrorCode.INVALID_CREDENTIALS, "The API key is not recognised.");
      return;
    }
    request.setAttribute(PRINCIPAL_ATTRIBUTE, new MerchantPrincipal(merchantId));
    chain.doFilter(request, response);
  }

  private void reject(HttpServletResponse response, ErrorCode code, String detail)
      throws IOException {
    ProblemDetail problem = problems.create(code, detail);
    response.setStatus(code.status().value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader("WWW-Authenticate", "Bearer");
    objectMapper.writeValue(response.getOutputStream(), problem);
  }
}
