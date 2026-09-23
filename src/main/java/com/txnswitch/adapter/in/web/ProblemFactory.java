/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import com.txnswitch.config.ProblemProperties;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Builds RFC 9457 problem documents.
 *
 * <p>One place, so that every failure carries the same members: a stable {@code code}, a {@code
 * type} that dereferences to the catalogue entry, and the correlation id that ties the response to
 * the logs.
 */
@Component
public class ProblemFactory {

  private final String typeBaseUri;

  public ProblemFactory(ProblemProperties properties) {
    this.typeBaseUri = properties.typeBaseUri();
  }

  public ProblemDetail create(ErrorCode code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
    problem.setType(URI.create(typeBaseUri + code.name().toLowerCase(Locale.ROOT)));
    problem.setTitle(code.title());
    problem.setProperty("code", code.name());
    problem.setProperty("correlationId", CorrelationIdFilter.current());
    return problem;
  }
}
