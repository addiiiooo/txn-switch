/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.acquirer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Resolves the acquirer's base URL, including the {@code self} special case.
 *
 * <p>{@code self} is resolved once the web server reports the port it actually bound to, so a test
 * on a random port and a container on 8080 use identical configuration.
 */
@Component
public class AcquirerEndpoint implements ApplicationListener<WebServerInitializedEvent> {

  private static final Logger log = LoggerFactory.getLogger(AcquirerEndpoint.class);
  private static final String SELF = "self";
  private static final String SIMULATOR_PATH = "/__simulator/acquirer";

  private volatile String baseUrl;
  private final boolean self;

  public AcquirerEndpoint(AcquirerProperties properties) {
    this.self = SELF.equalsIgnoreCase(properties.baseUrl());
    this.baseUrl = properties.baseUrl();
  }

  @Override
  public void onApplicationEvent(WebServerInitializedEvent event) {
    if (self) {
      baseUrl = "http://localhost:" + event.getWebServer().getPort() + SIMULATOR_PATH;
      log.info("Acquirer calls are routed to the bundled simulator at {}", baseUrl);
    } else {
      log.info("Acquirer calls are routed to {}", baseUrl);
    }
  }

  public String url(String path) {
    return baseUrl + path;
  }
}
