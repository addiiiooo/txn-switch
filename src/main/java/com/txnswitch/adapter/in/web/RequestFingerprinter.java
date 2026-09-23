/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Fingerprints a request so that a repeated idempotency key can be checked against it.
 *
 * <p>The hash is taken over the <em>bound</em> request rather than the raw bytes, canonicalised
 * with sorted keys. Two bodies that differ only in field order or whitespace are the same request
 * and must replay; two that differ in a value are not, and must be refused. Hashing raw bytes would
 * turn a reformatted-but-identical retry into a 422, which is the wrong answer to give a client
 * that did nothing wrong.
 *
 * <p>The consequence, stated plainly: a field the server ignores does not change the fingerprint.
 * That is correct as long as an ignored field cannot change behaviour, which is exactly what being
 * ignored means.
 */
@Component
public class RequestFingerprinter {

  private final ObjectMapper mapper;
  private final ObjectMapper canonical;

  public RequestFingerprinter(ObjectMapper mapper) {
    this.mapper = mapper;
    this.canonical = mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  public String fingerprint(String method, String path, Object body) {
    try {
      String canonicalJson = canonical.writeValueAsString(mapper.convertValue(body, Object.class));
      return sha256(method + " " + path + "\n" + canonicalJson);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot fingerprint the request", e);
    }
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
}
