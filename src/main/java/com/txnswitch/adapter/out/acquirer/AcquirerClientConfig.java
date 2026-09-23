/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.acquirer;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class AcquirerClientConfig {

  /**
   * A pooling HTTP client with real socket timeouts.
   *
   * <p>The JDK client is used rather than {@code SimpleClientHttpRequestFactory} because the latter
   * opens a fresh connection per request, which turns fifty concurrent authorizations into fifty
   * TCP handshakes and measures the wrong thing.
   */
  @Bean
  RestClient acquirerRestClient(AcquirerProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(properties.readTimeout());
    return RestClient.builder().requestFactory(factory).build();
  }
}
