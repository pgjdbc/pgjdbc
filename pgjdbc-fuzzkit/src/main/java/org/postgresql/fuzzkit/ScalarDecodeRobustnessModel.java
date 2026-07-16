/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecFormatSupport;
import org.postgresql.api.codec.Format;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.OfflineCodecs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The descriptor list the uniform scalar decode-robustness targets are generated from, shared by every
 * engine's source generator and seed generator. Both the Jazzer and the JQF modules build their generated
 * {@code GeneratedScalarDecodeRobustnessFuzzTest} from this one model, so the two engines drive the same
 * scalar surface.
 *
 * <p>Each built-in scalar leaf codec ({@link OfflineCodecs#defaultRegistry()}{@code .builtinCodecsByOid()})
 * yields, in OID order:
 * <ul>
 *   <li>a {@code _binary} target and its offset-aware {@code _binaryOffset} sibling when the codec can read
 *       binary ({@link CodecFormatSupport#canReadBinary});</li>
 *   <li>a {@code _text} target when the codec can read text ({@link CodecFormatSupport#canReadText}).</li>
 * </ul>
 * Containers (array, composite, range, multirange) resolve by {@code typtype}, not by OID, so they are
 * absent from that map and stay hand-enumerated in each module's other fuzz classes; this model is exactly
 * the uniform scalar surface.
 *
 * <p>A binary target of a single-byte type (only {@code "char"}, OID 18) is marked
 * {@link Target#enumeratedByteDomain() enumerated}: its wire domain is finite (the empty wire plus every
 * byte {@code 0x00..0xFF}), so each engine's generator emits it as an exhaustive {@code @ParameterizedTest}
 * over that domain rather than a sampling {@code @FuzzTest}. The {@code (oid, format)} coverage is unchanged
 * -- the target still exists for {@code (char, binary)} and its offset sibling -- only its generated shape
 * differs.
 *
 * <p>An override marks a target {@link Target#disabled() disabled} with a reason the generator turns into a
 * JUnit {@code @Disabled}. The table is empty today: every generated target runs. It is the hook for a
 * scalar decode target that must be held out (an unfixed heap or recursion finding that a guided campaign
 * would keep re-hitting); {@code numeric} binary carries such a finding (F3, an unbounded wire-declared
 * digit-array length -- see pgjdbc-jqf-test/COERCION_MIGRATION.md), but it stays enabled, matching its
 * hand-written predecessor and its {@code bit}/{@code varbit} peers that share the defect, because the
 * bounded regression corpus never reaches the pathological length; only a guided campaign does.
 */
public final class ScalarDecodeRobustnessModel {

  private ScalarDecodeRobustnessModel() {
  }

  /**
   * One generated target: its OID, wire format, offset variant, name, disabled state, and whether its wire
   * domain is finite and enumerable ({@link #enumeratedByteDomain()}).
   */
  public static final class Target {
    private final int oid;
    private final String typeName;
    private final Format format;
    private final boolean offsetVariant;
    private final boolean enumeratedByteDomain;
    private final String methodName;
    private final String disabledReason;

    Target(int oid, String typeName, Format format, boolean offsetVariant, boolean enumeratedByteDomain,
        String disabledReason) {
      this.oid = oid;
      this.typeName = typeName;
      this.format = format;
      this.offsetVariant = offsetVariant;
      this.enumeratedByteDomain = enumeratedByteDomain;
      this.methodName = ScalarDecodeRobustnessNaming.methodName(oid, format, offsetVariant);
      this.disabledReason = disabledReason;
    }

    public int oid() {
      return oid;
    }

    public String typeName() {
      return typeName;
    }

    public Format format() {
      return format;
    }

    public boolean offsetVariant() {
      return offsetVariant;
    }

    /**
     * Whether this target's wire domain is finite and enumerable, so the generator emits an exhaustive
     * {@code @ParameterizedTest} over every wire rather than a sampling {@code @FuzzTest}. Only a binary
     * target of a single-byte type ({@code "char"}) qualifies; see {@link #enumerableBinaryDomain(int)}.
     *
     * @return true when the target is generated as an exhaustive parameterized test
     */
    public boolean enumeratedByteDomain() {
      return enumeratedByteDomain;
    }

    public String methodName() {
      return methodName;
    }

    public boolean disabled() {
      return !disabledReason.isEmpty();
    }

    /** The {@code @Disabled} reason; empty when the target is enabled. */
    public String disabledReason() {
      return disabledReason;
    }
  }

  /** The generated targets, in OID order, then binary, binary-offset, text within each OID. */
  public static List<Target> targets() {
    List<Target> targets = new ArrayList<>();
    // TreeMap copy pins OID order so the generated file and seed corpus are byte-for-byte reproducible.
    Map<Integer, Codec> byOid = new TreeMap<>(OfflineCodecs.defaultRegistry().builtinCodecsByOid());
    for (Map.Entry<Integer, Codec> entry : byOid.entrySet()) {
      int oid = entry.getKey();
      Codec codec = entry.getValue();
      String typeName = ScalarDecodeRobustnessNaming.typeName(oid);
      if (CodecFormatSupport.canReadBinary(codec)) {
        boolean enumerable = enumerableBinaryDomain(oid);
        targets.add(new Target(oid, typeName, Format.BINARY, false, enumerable, disabledReason(oid, Format.BINARY)));
        targets.add(new Target(oid, typeName, Format.BINARY, true, enumerable, disabledReason(oid, Format.BINARY)));
      }
      if (CodecFormatSupport.canReadText(codec)) {
        // The text wire is an arbitrary-length literal, so it is never a finite enumerable domain.
        targets.add(new Target(oid, typeName, Format.TEXT, false, false, disabledReason(oid, Format.TEXT)));
      }
    }
    requireUniqueMethodNames(targets);
    return targets;
  }

  // Two targets that compile to the same @FuzzTest method name would not compile, so fail fast while
  // building the model rather than in one engine's separate test. Both engines' generators build from this
  // list, so the check belongs here. A collision means two OIDs share a type name; disambiguate them in
  // ScalarDecodeRobustnessNaming.
  private static void requireUniqueMethodNames(List<Target> targets) {
    Set<String> seen = new HashSet<>();
    Set<String> duplicates = new TreeSet<>();
    for (Target target : targets) {
      if (!seen.add(target.methodName())) {
        duplicates.add(target.methodName());
      }
    }
    if (!duplicates.isEmpty()) {
      throw new IllegalStateException("Generated @FuzzTest method names must be unique; a collision would "
          + "fail to compile. Disambiguate the colliding built-in type names in ScalarDecodeRobustnessNaming: "
          + duplicates);
    }
  }

  // A scalar whose binary wire is a single byte, so its whole binary domain is finite and enumerable:
  // the empty wire (the '\0' value) plus every one-byte value 0x00..0xFF. "char" (OID 18) is the sole
  // built-in scalar with this property -- charsend emits the one byte, or nothing for '\0'. Its binary
  // targets are generated as an exhaustive @ParameterizedTest over that 257-input domain rather than a
  // sampling @FuzzTest, giving deterministic full-domain coverage; the arbitrary-length text wire is not
  // enumerable and stays a fuzz target.
  private static boolean enumerableBinaryDomain(int oid) {
    return oid == Oid.CHAR;
  }

  // The override table: quirks that hold a generated target out. Keyed by (oid, format) so both the
  // whole-array and offset-slice binary siblings of a held-out OID would inherit the same reason. Empty
  // today -- see the class javadoc for why numeric binary stays enabled despite finding F3.
  private static String disabledReason(@SuppressWarnings("UnusedVariable") int oid,
      @SuppressWarnings("UnusedVariable") Format format) {
    return "";
  }
}
