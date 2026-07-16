/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecFormatSupport;
import org.postgresql.api.codec.TextCodec;

import com.tngtech.archunit.core.domain.InstanceofCheck;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Keeps the format-capability decision in one place. The driver consumer layer
 * ({@code org.postgresql.jdbc} and its sub-packages, except the codec package) must decide whether a
 * codec reads or writes a wire format through {@link CodecFormatSupport}, never by
 * <ul>
 *   <li>calling {@code encodesBinary}/{@code decodesBinary}/{@code canEncodeBinary}/{@code decodesText}
 *       on a codec directly, nor</li>
 *   <li>testing {@code instanceof BinaryCodec}/{@code instanceof TextCodec} as a capability decision.</li>
 * </ul>
 * Both shapes are how the original F-3/F-4 drift crept in: a dispatch site that reads a capability off
 * the codec, or infers one from the interface, without going through the single predicate source.
 *
 * <p>Codec implementations in {@code org.postgresql.jdbc.codec} are exempt: they define their own
 * capability, forward a delegate's, or compose an element's. The codec-resolution factories in
 * {@link org.postgresql.jdbc.CodecRegistry} and the per-result-set codec accessors in
 * {@code org.postgresql.jdbc.PgResultSet} are exempt from the {@code instanceof} rule alone: they
 * narrow a {@link Codec} to {@link BinaryCodec}/{@link TextCodec} to hand the typed codec back
 * (method-fetch), not to decide a format. The scan is bytecode-level, so it catches a fully qualified
 * call or an {@code instanceof} a grep over source would miss.
 *
 * @see DelegatingCodecDepthGuardArchTest for the same bytecode-scan idiom
 */
class CodecCapabilityCallSiteArchTest {

  private static final JavaClasses JDBC_CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("org.postgresql.jdbc");

  /** The codec capability methods a decision must read through {@link CodecFormatSupport} instead. */
  private static final Set<String> CAPABILITY_METHODS = new LinkedHashSet<>(Arrays.asList(
      "encodesBinary", "decodesBinary", "canEncodeBinary", "decodesText"));

  /**
   * Consumer classes allowed to {@code instanceof BinaryCodec}/{@code TextCodec}: the resolution
   * factories that narrow a codec to hand it back typed, which is method-fetch, not a decision.
   * {@code CodecRegistry} narrows a freshly resolved codec; {@code PgResultSet} narrows the codec it
   * caches per result column, both to return the typed codec rather than to decide a format.
   */
  private static final Set<String> INSTANCEOF_ALLOWLIST =
      new LinkedHashSet<>(Arrays.asList("CodecRegistry", "PgResultSet"));

  @Test
  void consumerLayerDecidesFormatThroughCodecFormatSupport() {
    Set<String> violations = new TreeSet<>();
    for (JavaClass javaClass : JDBC_CLASSES) {
      if (!isConsumerLayer(javaClass)) {
        continue;
      }
      for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
        JavaClass owner = call.getTarget().getOwner();
        if (owner.isAssignableTo(Codec.class) && CAPABILITY_METHODS.contains(call.getName())) {
          violations.add(javaClass.getSimpleName() + " calls " + owner.getSimpleName() + "."
              + call.getName() + "()");
        }
      }
      if (!INSTANCEOF_ALLOWLIST.contains(javaClass.getSimpleName())) {
        for (JavaCodeUnit codeUnit : javaClass.getCodeUnits()) {
          for (InstanceofCheck check : codeUnit.getInstanceofChecks()) {
            JavaClass raw = check.getRawType();
            if (raw.isEquivalentTo(BinaryCodec.class) || raw.isEquivalentTo(TextCodec.class)) {
              violations.add(javaClass.getSimpleName() + " tests instanceof " + raw.getSimpleName());
            }
          }
        }
      }
    }

    assertEquals(emptyList(), new ArrayList<>(violations),
        "Consumer-layer classes deciding a codec's format capability directly instead of through "
            + "CodecFormatSupport -- either calling a capability method (encodesBinary/decodesBinary/"
            + "canEncodeBinary/decodesText) or testing instanceof BinaryCodec/TextCodec. Route the "
            + "decision through CodecFormatSupport.canReadBinary/canWriteBinary/canReadText/canWriteText "
            + "so the check stays in one place. A resolution factory that narrows a Codec to hand it "
            + "back typed belongs in INSTANCEOF_ALLOWLIST.");
  }

  private static boolean isConsumerLayer(JavaClass javaClass) {
    String pkg = javaClass.getPackageName();
    return pkg.startsWith("org.postgresql.jdbc")
        && !pkg.equals("org.postgresql.jdbc.codec")
        && !pkg.startsWith("org.postgresql.jdbc.codec.");
  }
}
