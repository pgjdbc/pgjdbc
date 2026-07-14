/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jazzer;

import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.ReadOracle;
import org.postgresql.fuzzkit.SqlInputReader;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.fuzzkit.coercion.ScalarDescriptor;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Emits one {@code Generated<Type>CoercionReaderFuzzTest.java} per read-populated scalar, replacing the
 * single hand-written {@code JazzerCoercionReaderFuzzTest} whose one {@code @FuzzTest} drew the field
 * type, reader, and target class as leading indices from a {@link
 * com.code_intelligence.jazzer.api.FuzzedDataProvider}. Jazzer keeps one corpus per method and mutates the
 * whole byte stream, so those leading indices made every mutation jump between unrelated
 * type/reader/class cells; the fuzzer could not specialise, and a bounded regression run reached only one
 * random cell of the matrix.
 *
 * <p>Materialising the matrix as concrete methods fixes both, and lets each cell pick the value strategy
 * its natural class deserves. A cell bakes in its (scalar, reader, target class), so the only free
 * dimension is the value, and a bounded regression run replays every cell once rather than a single random
 * one. A scalar whose whole domain is small enough to enumerate -- {@code bool} (and a {@code byte} scalar
 * were one registered) -- is a {@code @ParameterizedTest} over every value, because a coverage-guided
 * campaign cannot grow a two- or 256-value domain and would only burn its budget. A larger Jazzer-native
 * class -- the wider integral, floating-point, {@code byte[]}, and {@code String} types -- takes the value
 * straight as a typed {@code @FuzzTest} parameter ({@code byte @NotNull [] value} rather than
 * {@code JazzerValues.draw(data, byte[].class)}), so Jazzer's structure-aware mutator drives it directly; a
 * {@code String} is sanitised with {@code FuzzText.stripNul} first, since PostgreSQL text cannot carry a
 * NUL. Only {@code numeric} and the temporal types, which have no native encodable form (a date may fall
 * outside the wire range, {@code numeric} needs a bounded scale), still draw from {@link JazzerValues} over
 * a {@code FuzzedDataProvider}, the path that guarantees an encodable value, so
 * {@link org.postgresql.fuzzkit.CoercionFuzzSupport#run} never trips on an unencodable input.
 *
 * <p>The cells per scalar are every {@link SqlInputReader} except {@code READ_OBJECT_AS} (each carries its
 * own target class of {@code null}), plus one method per {@link ReadOracle#TARGET_CLASSES} entry for
 * {@code readObject(Class)}. {@code prefersJavaTime} is read as a byte of five flag bits, but only for the
 * temporal scalars ({@code date}, {@code time}, {@code timetz}, {@code timestamp}, {@code timestamptz})
 * whose decode depends on it; every other scalar passes all-false and spends no fuzzer entropy on an inert
 * axis.
 *
 * <p>Driven by {@link FuzzTargetGenerator}, the module's single generator entry point, which passes the
 * generated-sources root. It needs no database connection: the axes come from the offline descriptor
 * registry and the shared read oracle. Output is deterministic -- registration-ordered scalars, sorted
 * target classes -- so a rerun produces identical bytes.
 */
public final class CoercionReaderFuzzTestGenerator {

  private static final String PACKAGE = "org.postgresql.test.jazzer";
  private static final String NL = "\n";

  /** The scalars whose decode depends on {@code prefersJavaTime}; every other scalar passes all-false. */
  private static final Set<Integer> TEMPORAL_OIDS = temporalOids();

  private CoercionReaderFuzzTestGenerator() {
  }

  /**
   * Generates one {@code Generated<Type>CoercionReaderFuzzTest.java} per read-populated scalar under
   * {@code generatedSourcesRoot}.
   *
   * @param generatedSourcesRoot the generated-sources root (a {@code java} source directory) to write into
   * @throws IOException if a file cannot be written
   */
  public static void generate(Path generatedSourcesRoot) throws IOException {
    // One stable target-class -> method-suffix map, shared across every scalar class so a class name reads
    // the same everywhere and simple-name collisions (java.sql.Date vs java.util.Date) resolve once.
    Map<Class<?>, String> objectSuffixes = objectMethodSuffixes();

    Path packageDir = generatedSourcesRoot.resolve(PACKAGE.replace('.', '/'));
    Files.createDirectories(packageDir);

    int classes = 0;
    for (ScalarDescriptor descriptor : PgTypeDescriptors.readScalars()) {
      String className = className(descriptor.pgType().getFullName());
      String source = render(descriptor, className, objectSuffixes);
      Files.write(packageDir.resolve(className + ".java"), source.getBytes(StandardCharsets.UTF_8));
      classes++;
    }
    System.out.println("CoercionReaderFuzzTestGenerator: wrote " + classes
        + " Generated<Type>CoercionReaderFuzzTest classes to " + packageDir);
  }

  /** Renders the whole source file for one scalar's coercion-reader targets. */
  private static String render(ScalarDescriptor descriptor, String className,
      Map<Class<?>, String> objectSuffixes) {
    boolean temporal = TEMPORAL_OIDS.contains(descriptor.oid());
    String valueLiteral = classLiteral(descriptor.naturalClass());
    // Three value strategies, by natural class:
    //  - a small, exhaustively enumerable primitive domain (boolean, byte) uses @ParameterizedTest so JUnit
    //    drives every value once and no fuzzer budget is spent on a domain a campaign cannot grow;
    //  - a Jazzer-native class takes the value as a typed @FuzzTest parameter (a String is sanitised with
    //    FuzzText.stripNul first, since PostgreSQL text cannot carry a NUL; every other native value
    //    encodes as-is);
    //  - everything else draws from a FuzzedDataProvider through JazzerValues, the only encodable path.
    // The first two are never temporal, so they need no provider and no config byte. Only the byte[] and
    // String native types need the @NotNull import.
    @Nullable String valueSource = exhaustiveValueSource(descriptor.naturalClass());
    @Nullable String nativeType = nativeParamType(descriptor.naturalClass());
    String nativeValueExpr = nativeValueExpr(descriptor.naturalClass());
    boolean parameterized = valueSource != null;
    boolean needsFdp = nativeType == null;
    boolean needsNotNull = nativeType == null || nativeType.contains("@NotNull");
    boolean needsFuzzText = nativeType != null && nativeValueExpr.contains("FuzzText");

    StringBuilder sb = new StringBuilder(16_384);
    sb.append("/*").append(NL)
        .append(" * Copyright (c) 2026, PostgreSQL Global Development Group").append(NL)
        .append(" * See the LICENSE file in the project root for more information.").append(NL)
        .append(" */").append(NL).append(NL)
        .append("package ").append(PACKAGE).append(';').append(NL).append(NL);

    sb.append("import org.postgresql.fuzzkit.CoercionCase;").append(NL)
        .append("import org.postgresql.fuzzkit.CoercionFuzzSupport;").append(NL);
    if (needsFuzzText) {
      sb.append("import org.postgresql.fuzzkit.FuzzText;").append(NL);
    }
    sb.append("import org.postgresql.fuzzkit.SqlInputReader;").append(NL)
        .append("import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;").append(NL)
        .append("import org.postgresql.fuzzkit.coercion.ScalarDescriptor;").append(NL).append(NL);
    if (parameterized) {
      sb.append("import org.junit.jupiter.params.ParameterizedTest;").append(NL)
          .append("import org.junit.jupiter.params.provider.ValueSource;").append(NL);
    } else {
      if (needsFdp) {
        sb.append("import com.code_intelligence.jazzer.api.FuzzedDataProvider;").append(NL);
      }
      sb.append("import com.code_intelligence.jazzer.junit.FuzzTest;").append(NL);
      if (needsNotNull) {
        sb.append("import com.code_intelligence.jazzer.mutation.annotation.NotNull;").append(NL);
      }
    }
    sb.append(NL).append("import java.sql.SQLException;").append(NL).append(NL);

    appendClassJavadoc(sb, descriptor, temporal, parameterized, needsFdp);
    sb.append("class ").append(className).append(" {").append(NL).append(NL);
    sb.append("  private static final ScalarDescriptor DESCRIPTOR = PgTypeDescriptors.scalar(")
        .append(descriptor.oid()).append(");").append(NL);

    // The fixed readers: every SQLInput call except readObject(Class), each with a null target class.
    for (SqlInputReader reader : SqlInputReader.values()) {
      if (reader == SqlInputReader.READ_OBJECT_AS) {
        continue;
      }
      sb.append(NL);
      appendMethod(sb, readerMethodName(reader), valueLiteral, valueSource, nativeType, nativeValueExpr,
          temporal, "SqlInputReader." + reader.name(), "null");
    }

    // The readObject(Class) axis: one method per candidate target class.
    for (Map.Entry<Class<?>, String> entry : objectSuffixes.entrySet()) {
      String targetLiteral = classLiteral(entry.getKey());
      sb.append(NL);
      appendMethod(sb, "readObjectAs" + entry.getValue(), valueLiteral, valueSource, nativeType,
          nativeValueExpr, temporal, "SqlInputReader.READ_OBJECT_AS", targetLiteral);
    }

    sb.append('}').append(NL);
    return sb.toString();
  }

  private static void appendClassJavadoc(StringBuilder sb, ScalarDescriptor descriptor, boolean temporal,
      boolean parameterized, boolean needsFdp) {
    String valueSource;
    if (parameterized) {
      valueSource = "JUnit enumerates every value of this scalar's small domain through "
          + "{@code @ParameterizedTest}, because a coverage-guided campaign would only waste its budget on "
          + "a domain it cannot grow.";
    } else if (needsFdp) {
      valueSource = "Each method draws its value from {@code JazzerValues} over a "
          + "{@code FuzzedDataProvider} so it always encodes on the canonical wire.";
    } else if (descriptor.naturalClass() == String.class) {
      valueSource = "Jazzer mutates the value straight from each method's typed {@code String} parameter, "
          + "its NUL stripped so it encodes as PostgreSQL text.";
    } else {
      valueSource = "Jazzer mutates the value straight from each method's typed parameter, because this "
          + "scalar's natural class is Jazzer-native and always encodes.";
    }
    String prefersJavaTime = temporal
        ? "It varies {@code prefersJavaTime} through a byte of flag bits, because this scalar's decode "
            + "depends on it."
        : "It passes an all-false {@code prefersJavaTime}, because this scalar's decode does not depend on "
            + "it.";
    sb.append("/**").append(NL)
        .append(" * GENERATED with {@link CoercionReaderFuzzTestGenerator} -- do not edit. Regenerate with"
            + " {@code ./gradlew :pgjdbc-jazzer-test:generateJazzerFuzzTargets}.").append(NL)
        .append(" *").append(NL)
        .append(" * <p>The coercion-reader cells for {@code ").append(descriptor.pgType().getFullName())
        .append("} (OID ").append(descriptor.oid()).append("): one test method per {@link"
            + " org.postgresql.fuzzkit.SqlInputReader}, plus one per {@code readObject(Class)} target"
            + " class,").append(NL)
        .append(" * each asserting the reader returns or refuses with a {@code SQLException} and never"
            + " leaks. ").append(valueSource).append(' ').append(prefersJavaTime).append(NL)
        .append(" */").append(NL);
  }

  /** Appends one method that drives a single (reader, target class) cell of this scalar. */
  private static void appendMethod(StringBuilder sb, String methodName, String valueLiteral,
      @Nullable String valueSource, @Nullable String nativeType, String nativeValueExpr, boolean temporal,
      String readerLiteral, String targetLiteral) {
    // A typed-parameter cell (exhaustive @ParameterizedTest or native @FuzzTest); nativeType is the
    // primitive/array parameter and nativeValueExpr is the value handed to the case (a text value is
    // NUL-stripped, every other native value passes straight through). A native scalar is never temporal,
    // so the config axis is inert (byte 0) and no FuzzedDataProvider is needed.
    if (nativeType != null) {
      if (valueSource != null) {
        sb.append("  @ParameterizedTest").append(NL)
            .append("  @ValueSource(").append(valueSource).append(')').append(NL);
      } else {
        sb.append("  @FuzzTest").append(NL);
      }
      sb.append("  void ").append(methodName).append('(').append(nativeType).append(" value) throws"
          + " SQLException {").append(NL)
          .append("    CoercionFuzzSupport.run(new CoercionCase(DESCRIPTOR, ").append(nativeValueExpr)
          .append(", ").append(readerLiteral).append(", ").append(targetLiteral).append(", (byte) 0));")
          .append(NL)
          .append("  }").append(NL);
      return;
    }
    // A temporal scalar draws its prefersJavaTime config as one byte (CoercionCase unpacks the five low
    // bits); every other provider-drawn scalar's decode ignores the config, so it passes the all-false
    // byte 0.
    String prefersJavaTime = temporal ? "data.consumeByte()" : "(byte) 0";
    sb.append("  @FuzzTest").append(NL)
        .append("  void ").append(methodName).append("(@NotNull FuzzedDataProvider data) throws"
            + " SQLException {").append(NL)
        .append("    Object value = JazzerValues.draw(data, ").append(valueLiteral).append(");").append(NL)
        .append("    CoercionFuzzSupport.run(new CoercionCase(DESCRIPTOR, value, ").append(readerLiteral)
        .append(", ").append(targetLiteral).append(", ").append(prefersJavaTime).append("));").append(NL)
        .append("  }").append(NL);
  }

  /**
   * The exhaustive {@code @ValueSource} attribute for a value type whose whole domain is small enough to
   * enumerate as JUnit parameterized cases rather than fuzz, or {@code null} for a type that stays a
   * {@code @FuzzTest}. A {@code boolean} (two values) and a {@code byte} (256 values) are fully covered by
   * enumeration, so a coverage-guided campaign would only waste its budget; every larger native domain
   * ({@code short} upward, and the unbounded {@code byte[]}) keeps the fuzzer. No registered scalar has a
   * {@code Byte} natural class today, so only {@code bool} takes this path; the {@code byte} branch is the
   * ready generalisation.
   */
  private static @Nullable String exhaustiveValueSource(Class<?> naturalClass) {
    if (naturalClass == Boolean.class) {
      return "booleans = {false, true}";
    }
    if (naturalClass == Byte.class) {
      StringBuilder values = new StringBuilder("bytes = {");
      for (int b = Byte.MIN_VALUE; b <= Byte.MAX_VALUE; b++) {
        if (b > Byte.MIN_VALUE) {
          values.append(", ");
        }
        values.append(b);
      }
      return values.append('}').toString();
    }
    return null;
  }

  /**
   * The Jazzer-native {@code @FuzzTest} parameter type for a scalar's natural class, or {@code null} when
   * the value must be drawn from a {@code FuzzedDataProvider} through {@link JazzerValues}. A class maps to
   * a typed parameter when Jazzer mutates it natively and every native value can be made encodable on the
   * canonical wire: the integral, floating-point, {@code boolean}, and {@code byte[]} types encode as-is,
   * and the text types' {@link String} encodes once {@link #nativeValueExpr} strips its NUL. {@code numeric}'s
   * {@link java.math.BigDecimal} and the temporal types have no native encodable form, so they keep the
   * provider path. {@code Byte} is included for consistency with {@link #exhaustiveValueSource} even though
   * no registered scalar carries it today.
   */
  private static @Nullable String nativeParamType(Class<?> naturalClass) {
    if (naturalClass == Integer.class) {
      return "int";
    }
    if (naturalClass == Byte.class) {
      return "byte";
    }
    if (naturalClass == Long.class) {
      return "long";
    }
    if (naturalClass == Short.class) {
      return "short";
    }
    if (naturalClass == Float.class) {
      return "float";
    }
    if (naturalClass == Double.class) {
      return "double";
    }
    if (naturalClass == Boolean.class) {
      return "boolean";
    }
    if (naturalClass == byte[].class) {
      return "byte @NotNull []";
    }
    if (naturalClass == String.class) {
      return "@NotNull String";
    }
    return null;
  }

  /**
   * The expression handed to the {@code CoercionCase} as the value for a native-typed cell, given the
   * typed parameter {@code value}. A text value is sanitised through {@link org.postgresql.fuzzkit.FuzzText}
   * ({@code FuzzText.stripNul(value)}), because PostgreSQL text cannot carry a NUL and Jazzer's {@code String}
   * mutator can produce one; every other native value passes straight through. Only meaningful when
   * {@link #nativeParamType} is non-null.
   */
  private static String nativeValueExpr(Class<?> naturalClass) {
    return naturalClass == String.class ? "FuzzText.stripNul(value)" : "value";
  }

  /** {@code Generated<Type>CoercionReaderFuzzTest} for a scalar type name (for example {@code int4}). */
  private static String className(String typeName) {
    return "Generated" + Character.toUpperCase(typeName.charAt(0)) + typeName.substring(1)
        + "CoercionReaderFuzzTest";
  }

  /**
   * The method name for a fixed reader, derived from its enum constant: {@code READ_BIG_DECIMAL} becomes
   * {@code readBigDecimal}. The names are unique across the reader set, so no disambiguation is needed.
   */
  private static String readerMethodName(SqlInputReader reader) {
    String[] words = reader.name().toLowerCase(Locale.ROOT).split("_");
    StringBuilder name = new StringBuilder(reader.name().length());
    for (int i = 0; i < words.length; i++) {
      if (i == 0) {
        name.append(words[i]);
      } else {
        name.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
      }
    }
    return name.toString();
  }

  /**
   * The stable {@code readObjectAs<Suffix>} suffix for every candidate target class, in class-name order.
   * A simple name shared by two classes ({@code java.sql.Date} and {@code java.util.Date}) is prefixed
   * with its last package segment ({@code SqlDate}, {@code UtilDate}) so the two methods do not collide.
   */
  private static Map<Class<?>, String> objectMethodSuffixes() {
    Class<?>[] classes = ReadOracle.TARGET_CLASSES;
    Map<String, Integer> simpleNameCounts = new HashMap<>();
    for (Class<?> target : classes) {
      String simple = identifier(target.getSimpleName());
      simpleNameCounts.merge(simple, 1, Integer::sum);
    }
    // Sort by class name so the suffix and thus the emitted method order is stable across runs.
    Class<?>[] sorted = classes.clone();
    Arrays.sort(sorted, Comparator.comparing(Class::getName));
    Map<Class<?>, String> suffixes = new LinkedHashMap<>();
    for (Class<?> target : sorted) {
      String simple = identifier(target.getSimpleName());
      String suffix = simpleNameCounts.getOrDefault(simple, 0) > 1
          ? identifier(lastPackageSegment(target)) + simple
          : simple;
      // Capitalise the leading character so a primitive-array leaf (byte[] -> byteArray) reads as
      // readObjectAsByteArray, matching the class-derived suffixes that already start upper-case.
      suffixes.put(target, Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1));
    }
    return suffixes;
  }

  /** The last segment of a class's package ({@code java.sql} -> {@code Sql}), capitalised. */
  private static String lastPackageSegment(Class<?> target) {
    Package targetPackage = target.getPackage();
    String packageName = targetPackage == null ? "" : targetPackage.getName();
    int dot = packageName.lastIndexOf('.');
    String segment = dot < 0 ? packageName : packageName.substring(dot + 1);
    return segment.isEmpty() ? "" : Character.toUpperCase(segment.charAt(0)) + segment.substring(1);
  }

  /** A class literal in fully-qualified form: {@code java.math.BigDecimal.class}, {@code byte[].class}. */
  private static String classLiteral(Class<?> type) {
    return type.getCanonicalName() + ".class";
  }

  /** Turns a simple name into a valid identifier fragment: {@code byte[]} -> {@code byteArray}. */
  private static String identifier(String simpleName) {
    return simpleName.replace("[]", "Array").replaceAll("[^A-Za-z0-9]", "");
  }

  private static Set<Integer> temporalOids() {
    Set<Integer> oids = new HashSet<>();
    oids.add(Oid.DATE);
    oids.add(Oid.TIME);
    oids.add(Oid.TIMETZ);
    oids.add(Oid.TIMESTAMP);
    oids.add(Oid.TIMESTAMPTZ);
    return oids;
  }
}
