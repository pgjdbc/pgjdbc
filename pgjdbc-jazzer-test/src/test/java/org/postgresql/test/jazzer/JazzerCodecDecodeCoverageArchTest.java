/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jazzer;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.postgresql.api.codec.Codec;
import org.postgresql.fuzzkit.ContainerDecodeTypes;
import org.postgresql.jdbc.OfflineCodecs;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/**
 * The drift guard for decode-robustness <b>coverage</b>: it enumerates every concrete {@link Codec} in
 * {@code org.postgresql.jdbc.codec} and asserts each is reached by a decode-robustness target -- the
 * feed-arbitrary-input-never-leak property -- or is a documented exclusion. This is the guard that would
 * have caught the {@code MultirangeCodec} gap: a delegating codec added to the driver with no target here
 * fails the build until it gains a target or an exclusion.
 *
 * <p>The registry's {@link OfflineCodecs#defaultRegistry()}{@code .builtinCodecsByOid()} only lists the
 * pinned <em>scalar</em> codecs; the delegating codecs (array, composite, range, multirange, domain) are
 * resolved on demand by {@code typtype} and appear in no enumerable map, so the universe of codec classes
 * cannot come from the registry. ArchUnit's {@link ClassFileImporter} supplies it by scanning the package.
 *
 * <p>A codec is "reached" when it is resolved by one of the two decode-robustness surfaces:
 * <ul>
 *   <li>the scalar family ({@code GeneratedScalarDecodeRobustnessFuzzTest}) reaches every built-in scalar
 *       codec -- one target per readable {@code (oid, format)} of every entry in
 *       {@code builtinCodecsByOid()};</li>
 *   <li>the container families ({@link JazzerBinaryContainerDecodeFuzzTest} /
 *       {@link JazzerTextLiteralDecodeFuzzTest}) reach the delegating codec behind each
 *       {@link ContainerDecodeTypes} entry.</li>
 * </ul>
 *
 * <p>The exclusions are codec classes with no standalone decode surface: the {@code *ArrayLeafCodec} array
 * element machinery (exercised transitively when {@code int4[]}/{@code text[]} decode), and a small set of
 * codecs that either forward to another codec or have no pinned built-in type to fuzz. Each is named with
 * its reason; a stale exclusion (a class that no longer exists) fails the test too.
 */
class JazzerCodecDecodeCoverageArchTest {

  private static final String CODEC_PACKAGE = "org.postgresql.jdbc.codec";

  // Concrete codecs with no standalone decode-robustness target, each with the reason it needs none.
  private static final Set<String> EXCLUDED = new TreeSet<>(Arrays.asList(
      "DomainCodec",    // forwards decode to its base codec; the base is covered by the scalar family
      "PGobjectCodec",  // generic fallback for custom PGobject types; no pinned built-in type to fuzz
      "FallbackCodec",  // last-resort codec for an unknown OID; no pinned type
      "TextLikeCodec",  // text-send codec-less types (refcursor, extensions); resolved dynamically, no OID
      "EnumCodec",      // needs a specific enum type; no offline built-in to decode-fuzz
      "HstoreCodec"     // hstore is an extension type, not a pinned built-in
  ));

  @Test
  void everyCodecClassHasADecodeRobustnessTargetOrIsExcluded() throws SQLException {
    Set<String> concrete = concreteCodecSimpleNames();
    assertFalse(concrete.isEmpty(), "codec package scan found no concrete Codec classes");

    Set<String> reached = new TreeSet<>();
    for (Codec codec : OfflineCodecs.defaultRegistry().builtinCodecsByOid().values()) {
      reached.add(codec.getClass().getSimpleName());
    }
    for (ContainerDecodeTypes.TypeInContext container : ContainerDecodeTypes.all()) {
      reached.add(container.context().resolveCodec(container.type().getOid()).getClass().getSimpleName());
    }

    Set<String> missing = new TreeSet<>(concrete);
    missing.removeAll(reached);
    missing.removeIf(JazzerCodecDecodeCoverageArchTest::isExcluded);
    assertEquals(emptyList(), new ArrayList<>(missing),
        "Concrete Codec classes with no decode-robustness target. A built-in scalar OID is reached by the "
            + "generated scalar family; a delegating container needs a target in "
            + "JazzerBinaryContainerDecodeFuzzTest/JazzerTextLiteralDecodeFuzzTest plus an entry in "
            + "ContainerDecodeTypes. Otherwise add it to EXCLUDED with a reason.");

    Set<String> staleExclusions = new TreeSet<>(EXCLUDED);
    staleExclusions.removeAll(concrete);
    assertEquals(emptyList(), new ArrayList<>(staleExclusions),
        "EXCLUDED names codec classes that no longer exist in " + CODEC_PACKAGE + ". Remove them.");
  }

  private static boolean isExcluded(String simpleName) {
    // Array element codecs are ArrayCodec's internal machinery, exercised transitively via the array
    // decode targets, so they carry no standalone target.
    return simpleName.endsWith("ArrayLeafCodec") || EXCLUDED.contains(simpleName);
  }

  /** Every concrete {@link Codec} implementation declared directly in the codec package. */
  private static Set<String> concreteCodecSimpleNames() {
    Set<String> names = new TreeSet<>();
    for (JavaClass codecClass : new ClassFileImporter().importPackages(CODEC_PACKAGE)) {
      if (codecClass.getPackageName().equals(CODEC_PACKAGE)
          && !codecClass.getName().contains("$")
          && codecClass.isAssignableTo(Codec.class)
          && !codecClass.isInterface()
          && !codecClass.getModifiers().contains(JavaModifier.ABSTRACT)) {
        names.add(codecClass.getSimpleName());
      }
    }
    return names;
  }
}
