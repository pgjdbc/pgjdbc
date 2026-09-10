/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.arch;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.Driver;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Applies {@link StreamArchRules} to the driver's production classes. Test classes are out of
 * scope: several of them implement a stream that misbehaves on purpose.
 */
class StreamArchTest {
  /**
   * Streams that still inherit {@link InputStream#available()}. Each entry is a bug rather than an
   * exemption, and the list only shrinks: a stream that trips the rule has to implement
   * {@code available()} instead of joining this list. {@link #knownAvailableGapsAreStillReal()}
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
        .check(PRODUCTION_CLASSES.that(notIn(KNOWN_AVAILABLE_GAPS)));
  }

  @Test
  void inputStreamsOverrideBulkRead() {
    StreamArchRules.inputStreamsShouldOverrideBulkRead().check(PRODUCTION_CLASSES);
  }

  @Test
  void outputStreamsOverrideBulkWrite() {
    StreamArchRules.outputStreamsShouldOverrideBulkWrite().check(PRODUCTION_CLASSES);
  }

  /**
   * Fails when a class listed in {@link #KNOWN_AVAILABLE_GAPS} no longer violates the rule, so that
   * fixing a stream also removes it from the list.
   */
  @Test
  void knownAvailableGapsAreStillReal() {
    for (String className : KNOWN_AVAILABLE_GAPS) {
      JavaClasses onlyThisClass = PRODUCTION_CLASSES.that(notIn(exclude(className)));
      assertThrows(AssertionError.class,
          () -> StreamArchRules.inputStreamsShouldOverrideAvailable().check(onlyThisClass),
          () -> className + " overrides available() now. Drop it from KNOWN_AVAILABLE_GAPS.");
    }
  }

  /**
   * Fails if the import produced no streams at all, which would leave the rules above passing
   * without having looked at anything.
   */
  @Test
  void importCoversTheDriverStreams() {
    long streams = PRODUCTION_CLASSES.stream()
        .filter(describe("input streams", c -> c.isAssignableTo(InputStream.class)))
        .count();
    assertTrue(streams >= 4,
        () -> "Expected the import to cover the driver's InputStream implementations, got "
            + streams + " in " + PRODUCTION_CLASSES.size() + " classes");
  }

  private static Set<String> exclude(String className) {
    Set<String> everythingElse = new HashSet<>(KNOWN_AVAILABLE_GAPS);
    everythingElse.remove(className);
    return everythingElse;
  }

  private static DescribedPredicate<JavaClass> notIn(Collection<String> classNames) {
    return describe("not one of " + classNames, c -> !classNames.contains(c.getName()));
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
