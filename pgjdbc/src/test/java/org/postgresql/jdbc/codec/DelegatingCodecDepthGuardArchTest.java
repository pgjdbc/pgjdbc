/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.Codec;
import org.postgresql.jdbc.CodecDepth;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reminder guard: every codec that delegates to another codec must reference {@link CodecDepth}, so a
 * newly added delegating codec that forgets to bound its recursion is caught at build time rather than
 * as a stack overflow at runtime.
 *
 * <p>"Delegates" is read from bytecode: the class either calls {@link
 * org.postgresql.api.codec.BinaryCodec#writeElement} or invokes a {@code decode*}/{@code encode*}
 * method on one of the {@code org.postgresql.api.codec} codec interfaces (a sibling codec resolved
 * from the registry, not its own overrides). {@code ArrayCodec} is an orchestrator that hands the
 * element codec to a leaf adapter and never calls either itself, so it is not flagged — the leaf
 * adapters ({@link org.postgresql.jdbc.codec.GenericArrayLeafCodec}) are, and they carry the guard.
 *
 * <p>The behavioural counterpart {@link CodecNestingDepthOfflineTest} proves the guard actually trips;
 * this test proves it is present on every delegating codec, including paths that test does not
 * enumerate.
 *
 * @see PrimitiveDecoderCoverageArchTest for the same acknowledged-exceptions pattern
 */
class DelegatingCodecDepthGuardArchTest {

  private static final JavaClasses CODEC_CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("org.postgresql.jdbc.codec");

  private static final String API_CODEC_PACKAGE = "org.postgresql.api.codec";

  /**
   * Codecs that delegate to another codec yet deliberately carry no depth guard of their own because
   * they cannot create unbounded recursion.
   *
   * <p>{@link JsonArrayLeafCodec} forwards each element to the {@code json}/{@code jsonb} scalar codec,
   * whose content is opaque text/bytes — it decodes to a {@code String}/{@code PGobject} and never
   * dispatches to a further codec, so the delegate is terminal.
   *
   * <p>{@link PGobjectCodec} is a thin 1:1 decorator: each operation forwards exactly one call to a
   * fixed delegate resolved at registration time. It never loops over sub-elements or resolves sibling
   * codecs, so it adds at most one frame per level; any recursion lives entirely in the delegate,
   * which carries its own guard when it is itself a container codec. A cycle back to a
   * {@code PGobjectCodec} must pass through that guarded delegate, so it stays bounded.
   */
  private static final Set<Class<?>> ACKNOWLEDGED_WITHOUT_OWN_GUARD =
      new LinkedHashSet<>(Arrays.asList(JsonArrayLeafCodec.class, PGobjectCodec.class));

  @Test
  void everyDelegatingCodecReferencesCodecDepth() {
    Set<String> missingGuard = new TreeSet<>();
    for (JavaClass javaClass : CODEC_CLASSES) {
      if (javaClass.isInterface()
          || javaClass.getModifiers().contains(JavaModifier.ABSTRACT)
          || !javaClass.isAssignableTo(Codec.class)) {
        continue;
      }
      if (!delegatesToAnotherCodec(javaClass) || referencesCodecDepth(javaClass)) {
        continue;
      }
      if (ACKNOWLEDGED_WITHOUT_OWN_GUARD.contains(javaClass.reflect())) {
        continue;
      }
      missingGuard.add(javaClass.getSimpleName());
    }

    assertEquals(emptyList(), new ArrayList<>(missingGuard),
        "Codecs that delegate to another codec (call BinaryCodec.writeElement or a decode*/encode* "
            + "method on an org.postgresql.api.codec interface) but never reference CodecDepth, so a "
            + "deeply nested or cyclic value can recurse without a depth bound and overflow the stack. "
            + "Wrap the delegation in CodecDepth.enter()/exit() -- or, if it cannot create unbounded "
            + "recursion (a terminal delegate, or a 1:1 decorator), add it to "
            + "ACKNOWLEDGED_WITHOUT_OWN_GUARD.");

    // Bidirectional: an acknowledged codec that gained a guard no longer belongs in the set.
    Set<String> staleAcknowledged = new TreeSet<>();
    for (Class<?> codecClass : ACKNOWLEDGED_WITHOUT_OWN_GUARD) {
      JavaClass javaClass = CODEC_CLASSES.get(codecClass);
      if (referencesCodecDepth(javaClass)) {
        staleAcknowledged.add(codecClass.getSimpleName());
      }
    }
    assertEquals(emptyList(), new ArrayList<>(staleAcknowledged),
        "Codecs listed in ACKNOWLEDGED_WITHOUT_OWN_GUARD that now reference CodecDepth, so the "
            + "acknowledgement is stale. Remove them from the acknowledged set.");
  }

  private static boolean delegatesToAnotherCodec(JavaClass javaClass) {
    for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
      JavaClass owner = call.getTarget().getOwner();
      if (!owner.getPackageName().equals(API_CODEC_PACKAGE) || !owner.isInterface()) {
        continue;
      }
      String method = call.getName();
      if (method.equals("writeElement")
          || method.startsWith("decode")
          || method.startsWith("encode")) {
        return true;
      }
    }
    return false;
  }

  private static boolean referencesCodecDepth(JavaClass javaClass) {
    for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
      if (call.getTarget().getOwner().isEquivalentTo(CodecDepth.class)) {
        return true;
      }
    }
    return false;
  }
}
