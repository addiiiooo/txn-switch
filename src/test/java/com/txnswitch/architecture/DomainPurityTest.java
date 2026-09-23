/* SPDX-License-Identifier: MIT */
package com.txnswitch.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The domain is meant to be plain Java, and a convention nobody checks is a convention that lasts
 * one refactor.
 *
 * <p>This reads the compiled class files rather than using reflection, so it catches framework
 * types wherever they appear — annotations of any retention, field and method signatures, even an
 * unused import that the compiler kept in the constant pool. Thirty lines, no extra dependency.
 */
class DomainPurityTest {

  private static final Path DOMAIN_CLASSES =
      Path.of("target", "classes", "com", "txnswitch", "domain");

  private static final List<String> FORBIDDEN =
      List.of(
          "org/springframework", "jakarta/persistence", "org/hibernate", "com/fasterxml/jackson");

  @Test
  void theDomainDependsOnNothingButTheJdk() throws IOException {
    assertThat(DOMAIN_CLASSES).as("compiled domain classes").exists();

    List<String> violations = new ArrayList<>();
    try (Stream<Path> classFiles = Files.walk(DOMAIN_CLASSES)) {
      for (Path classFile : classFiles.filter(p -> p.toString().endsWith(".class")).toList()) {
        String constantPool =
            new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
        for (String forbidden : FORBIDDEN) {
          if (constantPool.contains(forbidden)) {
            violations.add(classFile.getFileName() + " references " + forbidden);
          }
        }
      }
    }

    assertThat(violations).isEmpty();
  }

  @Test
  void theDomainPackageIsNotEmpty() throws IOException {
    try (Stream<Path> classFiles = Files.walk(DOMAIN_CLASSES)) {
      assertThat(classFiles.filter(p -> p.toString().endsWith(".class")).count())
          .as("this test is worthless if it scans nothing")
          .isGreaterThan(10);
    }
  }
}
