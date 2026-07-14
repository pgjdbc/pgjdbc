/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.api.codec.Codec;
import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.jdbc.OfflineCodecs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The descriptor list the primitive-capability parity targets are generated from, shared by every engine's
 * source generator. Both the Jazzer and the JQF modules build their generated
 * {@code GeneratedPrimitiveCapabilityFuzzTest} from this one model, so the two engines drive the same
 * outcome-parity properties for the opt-in primitive-capability interfaces
 * ({@code PrimitiveBinaryEncoder}, {@code PrimitiveTextEncoder}, {@code PrimitiveBinaryDecoder},
 * {@code PrimitiveTextDecoder}) and differ only in the engine that supplies the primitive argument.
 *
 * <p>Each target overrides a boxing default; the property is that the no-box override has the same
 * outcome as the boxing path -- the same bytes/value on success, and the same failure (same SQLState) on
 * a bad input. Each range-checked codec is fed its wider type -- an {@code int} to {@code int2}, a
 * {@code long} to {@code int4} -- so the overflow path is actually generated and the "too large a value
 * throws on both paths" property has teeth.
 *
 * <p>The <b>core</b> targets are DERIVED from the authoritative codec registry, not hand-listed: the loop
 * walks {@link OfflineCodecs#defaultRegistry()}{@code .builtinCodecsByOid()} (the same source the scalar
 * decode-robustness family uses) and, for every codec whose {@link Codec#getDefaultJavaType()} is a Java
 * primitive wrapper, emits a parity target keyed off that primitive -- {@code Short}/{@code Integer}
 * &rarr; an {@code int} target plus a {@code long} overflow sibling, {@code Long} &rarr; {@code long},
 * {@code Float}/{@code Double}/{@code Boolean} &rarr; their kind. So {@code int2}/{@code int4}/{@code int8}/
 * {@code oid}/{@code oid8}/{@code xid8}/{@code float4}/{@code float8}/{@code bool} appear automatically, and
 * a primitive type added to (or removed from) the registry follows without touching this class -- the whole
 * reason the targets are generated. {@code String}/{@code PGobject}/{@code BigDecimal} defaults (text,
 * {@code bit}, {@code numeric}) are not primitives, so those codecs contribute nothing.
 *
 * <p>This model records only what the default-Java-type signal cannot express on its own:
 * <ul>
 *   <li>{@code oid}'s unsigned-32-bit mask (its default type is {@code Long} like {@code int8}, but its
 *       wire value must be masked);</li>
 *   <li>{@code oid8}/{@code xid8}: unsigned 64-bit types with no coercion descriptor, tested against the
 *       {@code unsignedLongPrimitiveParity} oracle rather than {@code longPrimitiveParity} -- the
 *       version-gated comments are kept here on purpose;</li>
 *   <li>{@link #EXCLUDED}: a primitive-default codec that is deliberately NOT a parity target
 *       ({@code money}, whose default is {@code Double} but whose primitive accessors are coercions, not a
 *       natural-primitive identity);</li>
 *   <li>the domain targets, which forward to a base codec and are not standalone registry entries;</li>
 *   <li>the {@code int8} narrowing property, which is deliberately NOT a parity check.</li>
 * </ul>
 * A primitive-default codec that is none of the above and has no coercion descriptor (so the parity oracle
 * cannot resolve its {@code PgType} by OID) is a generation-time error: it must gain an override here or an
 * {@link #EXCLUDED} entry, so a new primitive type cannot slip in untested.
 *
 * <p>The body lines are engine-agnostic Java that both generators emit verbatim; the generated file is
 * compiled by the build, so a drift from a changed {@code CodecFuzzSupport} signature surfaces as a
 * generated-code compile error.
 */
public final class PrimitiveCapabilityModel {

  // Built-in codecs whose default Java type is a primitive wrapper yet which are NOT parity targets.
  // money's getDefaultJavaType is Double, but its primitive accessors are coercions (it has no matching
  // primitive encoder and no coercion descriptor), so the "no-box override == boxing default" oracle is
  // not the right one. A new such type triggers a generation-time error until it is listed here or given a
  // recipe above.
  private static final Set<Integer> EXCLUDED = Collections.singleton(Oid.MONEY);

  private PrimitiveCapabilityModel() {
  }

  /** One generated {@code @FuzzTest} target: its method name, primitive parameter type, and body. */
  public static final class Target {
    private final String methodName;
    private final String parameterType;
    private final List<String> precedingComment;
    private final List<String> body;

    Target(String methodName, String parameterType, List<String> precedingComment, List<String> body) {
      this.methodName = methodName;
      this.parameterType = parameterType;
      this.precedingComment = Collections.unmodifiableList(new ArrayList<>(precedingComment));
      this.body = Collections.unmodifiableList(new ArrayList<>(body));
    }

    /** The stable {@code @FuzzTest} method name, for example {@code int2Parity}. */
    public String methodName() {
      return methodName;
    }

    /** The Java primitive keyword of the single parameter ({@code int}, {@code long}, ...). */
    public String parameterType() {
      return parameterType;
    }

    /**
     * A comment block emitted immediately before the method (a section header shared by a group of
     * targets), or an empty list when the target has none. Never {@code null}.
     */
    public List<String> precedingComment() {
      return precedingComment;
    }

    /** The method body: source lines (inline comments and statements), without leading indentation. */
    public List<String> body() {
      return body;
    }
  }

  /** The generated targets: the registry-derived core first (OID-sorted), then the bespoke additions. */
  public static List<Target> targets() {
    List<Target> out = new ArrayList<>();

    // --- Core: one parity target per primitive-default built-in codec, so add/remove follows the registry.
    // TreeMap pins OID order so the generated file is byte-for-byte reproducible.
    Map<Integer, Codec> byOid = new TreeMap<>(OfflineCodecs.defaultRegistry().builtinCodecsByOid());
    for (Map.Entry<Integer, Codec> entry : byOid.entrySet()) {
      appendCoreParity(out, entry.getKey(), entry.getValue());
    }

    // --- Additions: not standalone registry codecs. ---
    // A domain forwards its base type's primitive accessors, so a domain over a pure codec inherits the
    // same outcome parity -- the no-box path that DomainCodec restores. A curated subset of the base types.
    List<String> domainHeader = Arrays.asList(
        "// A domain forwards its base type's primitive accessors, so a domain over a pure codec inherits the",
        "// same outcome parity -- the no-box path that DomainCodec restores.");
    out.add(domainParity("domainOverInt4Parity", "int", "domainIntPrimitiveParity", Oid.INT4, 'N',
        domainHeader));
    out.add(domainParity("domainOverInt8Parity", "long", "domainLongPrimitiveParity", Oid.INT8, 'N',
        Collections.emptyList()));
    out.add(domainParity("domainOverFloat8Parity", "double", "domainDoublePrimitiveParity", Oid.FLOAT8, 'N',
        Collections.emptyList()));
    out.add(domainParity("domainOverBoolParity", "boolean", "domainBooleanPrimitiveParity", Oid.BOOL, 'B',
        Collections.emptyList()));

    // A narrowing accessor (int8 -> int) is NOT outcome-parity with its boxing default, which truncates
    // via Long.intValue(); instead it must reject an out-of-range value rather than silently truncate.
    out.add(new Target("int8NarrowingRejectsOverflow", "long", Arrays.asList(
        "// A narrowing accessor (int8 -> int) is NOT outcome-parity with its boxing default, which truncates",
        "// via Long.intValue(); instead it must reject an out-of-range value rather than silently truncate."),
        Collections.singletonList(
            "CodecFuzzSupport.int8NarrowingRejectsOverflow(value, CodecFuzzSupport.builtins());")));

    return Collections.unmodifiableList(out);
  }

  /**
   * The built-in OIDs the parity family covers: every codec whose {@link Codec#getDefaultJavaType()} is a
   * primitive wrapper, minus {@link #EXCLUDED}. The coverage guard cross-checks this against the built-ins
   * that implement a primitive capability (a different signal -- {@code instanceof}), so a codec that gains
   * a capability without a primitive default, or a new primitive type, cannot silently drift out of the
   * parity surface.
   */
  public static Set<Integer> parityOids() {
    Set<Integer> oids = new TreeSet<>();
    for (Map.Entry<Integer, Codec> entry
        : OfflineCodecs.defaultRegistry().builtinCodecsByOid().entrySet()) {
      if (isPrimitiveWrapper(entry.getValue().getDefaultJavaType()) && !EXCLUDED.contains(entry.getKey())) {
        oids.add(entry.getKey());
      }
    }
    return oids;
  }

  // Appends the parity target(s) for a built-in codec whose default Java type is a primitive wrapper; a
  // codec of any other default type (text -> String, bit -> PGobject, numeric -> BigDecimal) contributes
  // nothing.
  private static void appendCoreParity(List<Target> out, int oid, Codec codec) {
    Class<?> defaultType = codec.getDefaultJavaType();
    if (!isPrimitiveWrapper(defaultType) || EXCLUDED.contains(oid)) {
      return;
    }
    String type = ScalarDecodeRobustnessNaming.typeName(oid);
    if (oid == Oid.OID) {
      // oid is an unsigned 32-bit value; mask so encodeBinary produces a canonical 4-byte wire.
      out.add(new Target(type + "Parity", "long", Collections.emptyList(), Arrays.asList(
          "// oid is an unsigned 32-bit value; mask so encodeBinary produces a canonical 4-byte wire.",
          parityCall("longPrimitiveParity", "value & 0xFFFFFFFFL", oid))));
      return;
    }
    if (oid == Oid.OID8) {
      out.add(unsignedParity(type + "Parity", oid, 'N', Arrays.asList(
          "// oid8 (PostgreSQL 18+) is an unsigned 64-bit value: every long bit pattern is a valid wire",
          "// value, and the text form is Long.toUnsignedString, not Long.toString -- see",
          "// unsignedLongPrimitiveParity's Javadoc for why it cannot share longPrimitiveParity's oracle.")));
      return;
    }
    if (oid == Oid.XID8) {
      out.add(unsignedParity(type + "Parity", oid, 'U', Arrays.asList(
          "// xid8 (PostgreSQL 13+) shares oid8's unsigned 64-bit wire shape and the same reason",
          "// unsignedLongPrimitiveParity, not longPrimitiveParity, is the right oracle.")));
      return;
    }
    // A standard descriptor-backed primitive scalar: the parity oracle resolves its PgType by OID, which
    // requires a coercion descriptor. A primitive-default codec without one and without a recipe above is
    // an unclassified type -- fail loudly rather than emit a target that throws at run time.
    if (PgTypeDescriptors.find(oid) == null) {
      throw new IllegalStateException("primitive-capable built-in oid=" + oid + " (default "
          + defaultType.getName() + ") has no coercion descriptor and no parity recipe in "
          + "PrimitiveCapabilityModel; add an override or list it in EXCLUDED");
    }
    if (defaultType == Short.class || defaultType == Integer.class) {
      // Range-checked: the int target feeds its own width, the wide sibling feeds a long to reach overflow.
      out.add(scalarParity(type + "Parity", "int", "intPrimitiveParity", oid));
      out.add(scalarParity(type + "WideParity", "long", "longPrimitiveParity", oid));
    } else if (defaultType == Long.class) {
      out.add(scalarParity(type + "Parity", "long", "longPrimitiveParity", oid));
    } else if (defaultType == Float.class) {
      out.add(scalarParity(type + "Parity", "float", "floatPrimitiveParity", oid));
    } else if (defaultType == Double.class) {
      out.add(scalarParity(type + "Parity", "double", "doublePrimitiveParity", oid));
    } else {
      out.add(scalarParity(type + "Parity", "boolean", "booleanPrimitiveParity", oid));
    }
  }

  private static boolean isPrimitiveWrapper(Class<?> type) {
    return type == Short.class || type == Integer.class || type == Long.class
        || type == Float.class || type == Double.class || type == Boolean.class;
  }

  private static Target scalarParity(String methodName, String parameterType, String helper, int oid) {
    return new Target(methodName, parameterType, Collections.emptyList(),
        Collections.singletonList(parityCall(helper, "value", oid)));
  }

  // A CodecFuzzSupport.<helper>(<value>, Oid.<NAME>, builtins()) statement; Oid.toString gives the
  // upper-case constant name, so the emitted OID reference stays readable and stays derived from the oid.
  private static String parityCall(String helper, String valueExpr, int oid) {
    return "CodecFuzzSupport." + helper + "(" + valueExpr + ", Oid." + Oid.toString(oid)
        + ", CodecFuzzSupport.builtins());";
  }

  private static Target unsignedParity(String methodName, int oid, char typcategory,
      List<String> precedingComment) {
    String type = ScalarDecodeRobustnessNaming.typeName(oid);
    List<String> body = Arrays.asList(
        "CodecFuzzSupport.unsignedLongPrimitiveParity(value, CodecFuzzSupport.scalar(Oid." + Oid.toString(oid)
            + ", \"" + type + "\", '" + typcategory + "'),",
        "    CodecFuzzSupport.builtins());");
    return new Target(methodName, "long", precedingComment, body);
  }

  private static Target domainParity(String methodName, String parameterType, String helper, int baseOid,
      char baseCategory, List<String> precedingComment) {
    String baseName = ScalarDecodeRobustnessNaming.typeName(baseOid);
    String body = "CodecFuzzSupport." + helper + "(value, Oid." + Oid.toString(baseOid) + ", \"" + baseName
        + "\", '" + baseCategory + "');";
    return new Target(methodName, parameterType, precedingComment, Collections.singletonList(body));
  }
}
