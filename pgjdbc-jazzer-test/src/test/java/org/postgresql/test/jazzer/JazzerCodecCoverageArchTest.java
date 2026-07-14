/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jazzer;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecFormatSupport;
import org.postgresql.api.codec.Format;
import org.postgresql.fuzzkit.ScalarDecodeRobustnessModel;
import org.postgresql.fuzzkit.ScalarDecodeRobustnessModel.Target;
import org.postgresql.jdbc.OfflineCodecs;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Sanity checks for the generated scalar decode-robustness targets and the hand-written registry.
 *
 * <p>Completeness of the scalar decode surface is guaranteed by construction: {@link ScalarDecodeRobustnessModel}
 * emits one target per readable {@code (oid, format)} of every built-in scalar codec, so there is no
 * hand-maintained allow-list to rot and no acknowledged gap. These tests only guard that the model emits a
 * target for every readable {@code (oid, format)}, and that the hand-written registry stays consistent with
 * the physically-compiled {@code @FuzzTest} methods. Method-name uniqueness is enforced by the model itself
 * ({@code ScalarDecodeRobustnessModel.targets()} throws on a collision), not here.
 *
 * <p>There is no container-completeness check: the container targets (array, composite, range) are
 * deliberately hand-enumerated, with no mechanical invariant to assert against.
 */
class JazzerCodecCoverageArchTest {

  /** The hand-written fuzz classes registered in {@link JazzerFuzzTargets} (the generated classes are not). */
  private static final Class<?>[] HAND_WRITTEN_FUZZ_CLASSES = {
      JazzerScalarCodecFuzzTest.class,
      JazzerBinaryContainerDecodeFuzzTest.class,
      JazzerTextLiteralDecodeFuzzTest.class,
  };

  @Test
  void generatedModelCoversEveryReadableBuiltinScalar() {
    // Expected: one target per readable (oid, format) of every built-in scalar codec, plus the offset-aware
    // sibling for each binary target. Derived straight from the registry and the capability predicates.
    Set<String> expected = new TreeSet<>();
    for (Map.Entry<Integer, Codec> entry : OfflineCodecs.defaultRegistry().builtinCodecsByOid()
        .entrySet()) {
      int oid = entry.getKey();
      Codec codec = entry.getValue();
      if (CodecFormatSupport.canReadBinary(codec)) {
        expected.add(key(oid, Format.BINARY, false));
        expected.add(key(oid, Format.BINARY, true));
      }
      if (CodecFormatSupport.canReadText(codec)) {
        expected.add(key(oid, Format.TEXT, false));
      }
    }
    assertFalse(expected.isEmpty(), "the built-in scalar codec registry is empty; nothing to generate");

    Set<String> actual = new TreeSet<>();
    for (Target target : ScalarDecodeRobustnessModel.targets()) {
      actual.add(key(target.oid(), target.format(), target.offsetVariant()));
    }

    assertEquals(expected, actual,
        "ScalarDecodeRobustnessModel must emit one target per readable (oid, format) of every built-in scalar "
            + "codec, plus a binary-offset sibling -- no codec silently filtered out, no spurious target.");
  }

  @Test
  void everyHandWrittenFuzzTargetIsRegistered() {
    Set<String> registered = new TreeSet<>();
    for (JazzerFuzzTargets.Target target : JazzerFuzzTargets.all()) {
      registered.add(target.testClass().getSimpleName() + "#" + target.method());
    }

    Set<String> declared = new TreeSet<>();
    for (Class<?> fuzzClass : HAND_WRITTEN_FUZZ_CLASSES) {
      for (Method method : fuzzClass.getDeclaredMethods()) {
        if (method.isAnnotationPresent(FuzzTest.class)) {
          declared.add(fuzzClass.getSimpleName() + "#" + method.getName());
        }
      }
    }

    Set<String> unregistered = new TreeSet<>(declared);
    unregistered.removeAll(registered);
    assertEquals(emptyList(), new ArrayList<>(unregistered),
        "Hand-written @FuzzTest methods missing from JazzerFuzzTargets. Register each target so its seed "
            + "corpus is accounted for.");

    Set<String> phantom = new TreeSet<>(registered);
    phantom.removeAll(declared);
    assertEquals(emptyList(), new ArrayList<>(phantom),
        "JazzerFuzzTargets entries with no matching hand-written @FuzzTest method. Remove the stale entry.");
  }

  @Test
  void handWrittenTargetsReadOnlyRealBuiltins() {
    Set<Integer> builtins = OfflineCodecs.defaultRegistry().builtinCodecsByOid().keySet();
    Set<Integer> claimed = new TreeSet<>();
    claimed.addAll(JazzerFuzzTargets.readsBinaryOids());
    claimed.addAll(JazzerFuzzTargets.readsTextOids());
    claimed.removeAll(builtins);
    assertEquals(emptyList(), new ArrayList<>(claimed),
        "Hand-written targets claim to read OIDs with no built-in codec. Fix the OID in the registration.");
  }

  private static String key(int oid, Format format, boolean offsetVariant) {
    return oid + ":" + format + (offsetVariant ? ":offset" : "");
  }
}
