/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.arch;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.Driver;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import java.io.InputStream;
import java.net.URL;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Applies {@link StreamArchRules} to the driver's production classes. Test classes are out of
 * scope: several of them implement a stream that misbehaves on purpose.
 */
class StreamArchTest {
  /**
   * Streams that still inherit {@link InputStream#available()}. Each entry is a bug rather than an
   * exemption, and the list only shrinks: a stream that violates the rule has to implement
   * {@code available()} instead of joining this list. {@link #knownAvailableGapIsStillReal(String)}
   * fails once one of them is fixed and the entry is left behind.
   */
  private static final Set<String> KNOWN_AVAILABLE_GAPS = new HashSet<>(Arrays.asList(
      // https://github.com/pgjdbc/pgjdbc/pull/4383
      "org.postgresql.gss.GSSInputStream",
      "org.postgresql.largeobject.BlobInputStream",
      "org.postgresql.util.ReaderInputStream"));

  private static final JavaClasses PRODUCTION_CLASSES = importProductionClasses();

  @Test
  void inputStreamsOverrideAvailable() {
    StreamArchRules.inputStreamsShouldOverrideAvailable()
        .check(PRODUCTION_CLASSES.that(describe("not one of " + KNOWN_AVAILABLE_GAPS,
            c -> !KNOWN_AVAILABLE_GAPS.contains(c.getName()))));
  }

  @Test
  void outputStreamsOverrideBulkWrite() {
    StreamArchRules.outputStreamsShouldOverrideBulkWrite().check(PRODUCTION_CLASSES);
  }

  /**
   * Fails when {@code available()} of a class listed in {@link #KNOWN_AVAILABLE_GAPS} no longer
   * resolves to {@link InputStream#available()}, so that the change that fixes a stream also removes
   * its entry. A listed class that no longer exists
   * fails too, because ArchUnit throws when it evaluates a rule over no classes.
   */
  @ParameterizedTest
  @FieldSource("KNOWN_AVAILABLE_GAPS")
  void knownAvailableGapIsStillReal(String className) {
    JavaClasses gap = PRODUCTION_CLASSES.that(describe("named " + className,
        c -> c.getName().equals(className)));
    assertTrue(StreamArchRules.inputStreamsShouldOverrideAvailable().evaluate(gap).hasViolation(),
        () -> className + " no longer inherits InputStream.available(). Drop it from "
            + "KNOWN_AVAILABLE_GAPS.");
  }

  private static JavaClasses importProductionClasses() {
    CodeSource codeSource = Driver.class.getProtectionDomain().getCodeSource();
    if (codeSource == null) {
      throw new IllegalStateException("Unable to locate the code source of " + Driver.class);
    }
    URL location = codeSource.getLocation();
    return new ClassFileImporter().importUrl(location)
        .that(describe("reside in org.postgresql",
            c -> c.getPackageName().startsWith("org.postgresql")));
  }
}
