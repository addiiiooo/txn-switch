/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@code docs/errors.md} and {@link ErrorCode} honest about each other.
 *
 * <p>A documented error catalogue drifts from the code within a month unless something checks it,
 * and a wrong catalogue is worse than none: clients write their retry logic against it.
 */
class ErrorCatalogueTest {

  private static final Path CATALOGUE = Path.of("docs", "errors.md");
  private static final Pattern SECTION = Pattern.compile("^### `([A-Z_]+)`$");
  private static final Pattern STATUS = Pattern.compile("^\\*\\*(\\d{3})\\*\\*.*");

  private static Map<String, Integer> documentedCodes() throws IOException {
    Map<String, Integer> documented = new LinkedHashMap<>();
    List<String> lines = Files.readAllLines(CATALOGUE);
    for (int i = 0; i < lines.size(); i++) {
      Matcher section = SECTION.matcher(lines.get(i));
      if (!section.matches()) {
        continue;
      }
      Integer status = null;
      for (int j = i + 1; j < Math.min(i + 5, lines.size()); j++) {
        Matcher statusLine = STATUS.matcher(lines.get(j).trim());
        if (statusLine.matches()) {
          status = Integer.parseInt(statusLine.group(1));
          break;
        }
      }
      documented.put(section.group(1), status);
    }
    return documented;
  }

  @Test
  void everyErrorCodeIsDocumentedAndEveryDocumentedCodeExists() throws IOException {
    assertThat(documentedCodes().keySet())
        .containsExactlyInAnyOrderElementsOf(
            Stream.of(ErrorCode.values()).map(Enum::name).toList());
  }

  @Test
  void theDocumentedStatusMatchesTheOneWeActuallyReturn() throws IOException {
    Map<String, Integer> documented = documentedCodes();
    List<String> mismatches = new ArrayList<>();
    for (ErrorCode code : ErrorCode.values()) {
      Integer status = documented.get(code.name());
      if (status == null || status != code.status().value()) {
        mismatches.add(
            code.name() + ": documented " + status + ", returns " + code.status().value());
      }
    }
    assertThat(mismatches).isEmpty();
  }

  @Test
  void everyCodeIsListedInTheIndexSoItsTypeUriResolves() throws IOException {
    String catalogue = Files.readString(CATALOGUE);
    for (ErrorCode code : ErrorCode.values()) {
      assertThat(catalogue)
          .as("index entry for %s", code)
          .contains(
              "[`" + code.name() + "`](#" + code.name().toLowerCase(java.util.Locale.ROOT) + ")");
    }
  }
}
