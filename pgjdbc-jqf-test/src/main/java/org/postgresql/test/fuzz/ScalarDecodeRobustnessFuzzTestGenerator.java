/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.api.codec.Format;
import org.postgresql.fuzzkit.ScalarDecodeRobustnessModel;
import org.postgresql.fuzzkit.ScalarDecodeRobustnessModel.Target;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Emits {@code GeneratedScalarDecodeRobustnessFuzzTest.java} from {@link ScalarDecodeRobustnessModel}, the
 * JQF sibling of the Jazzer {@code GeneratedScalarDecodeRobustnessFuzzTest}. Both engines generate the
 * uniform scalar decode-robustness targets from the shared model rather than hand-keeping them, so they
 * drive the same scalar surface and differ only in the engine that supplies the bytes.
 *
 * <p>JQF discovers real, physically compiled {@code @FuzzTest} methods and replays each one's saved corpus
 * (plus the empty input) as bounded regression, so the targets must be concrete methods with stable names.
 * This generator writes one such source file; the Gradle task compiles it into the test source set. Output
 * is deterministic -- OID-ordered, no timestamps -- so a rerun produces identical bytes.
 *
 * <p>Every generated method takes a {@code byte[]}, which {@link PgValueArgumentsFactory} draws from the
 * guided byte stream. The text targets feed the raw bytes to {@code decodeScalarTextBytesExpectingNoLeak},
 * reaching invalid-UTF-8 text wires a valid-{@code String} generator never produces -- the one behavioural
 * difference from the Jazzer generator, whose text targets take a {@code String}.
 *
 * <p>Driven by {@link FuzzTargetGenerator}, the module's single generator entry point, which passes the
 * generated-sources root. It needs no database connection: the model is built from the offline codec registry.
 */
public final class ScalarDecodeRobustnessFuzzTestGenerator {

  private static final String PACKAGE = "org.postgresql.test.fuzz";
  private static final String CLASS_NAME = "GeneratedScalarDecodeRobustnessFuzzTest";
  private static final String NL = "\n";

  private ScalarDecodeRobustnessFuzzTestGenerator() {
  }

  /**
   * Generates {@code GeneratedScalarDecodeRobustnessFuzzTest.java} under {@code generatedSourcesRoot}.
   *
   * @param generatedSourcesRoot the generated-sources root (a {@code java} source directory) to write into
   * @throws IOException if the file cannot be written
   */
  public static void generate(Path generatedSourcesRoot) throws IOException {
    List<Target> targets = ScalarDecodeRobustnessModel.targets();
    String source = render(targets);

    Path packageDir = generatedSourcesRoot.resolve(PACKAGE.replace('.', '/'));
    Files.createDirectories(packageDir);
    Path file = packageDir.resolve(CLASS_NAME + ".java");
    Files.write(file, source.getBytes(StandardCharsets.UTF_8));
    System.out.println("ScalarDecodeRobustnessFuzzTestGenerator: wrote " + targets.size() + " @FuzzTest targets to "
        + file);
  }

  /** Renders the whole source file for {@code targets}. */
  private static String render(List<Target> targets) {
    boolean anyDisabled = false;
    boolean anyEnumerated = false;
    for (Target target : targets) {
      anyDisabled |= target.disabled();
      anyEnumerated |= target.enumeratedByteDomain();
    }

    StringBuilder sb = new StringBuilder(8192);
    sb.append("/*").append(NL)
        .append(" * Copyright (c) 2026, PostgreSQL Global Development Group").append(NL)
        .append(" * See the LICENSE file in the project root for more information.").append(NL)
        .append(" */").append(NL).append(NL)
        .append("package ").append(PACKAGE).append(';').append(NL).append(NL);

    sb.append("import org.postgresql.fuzzkit.CodecFuzzSupport;").append(NL).append(NL)
        .append("import edu.berkeley.cs.jqf.junit5.FuzzTest;").append(NL);
    if (anyDisabled) {
      sb.append("import org.junit.jupiter.api.Disabled;").append(NL);
    }
    if (anyEnumerated) {
      sb.append("import org.junit.jupiter.params.ParameterizedTest;").append(NL)
          .append("import org.junit.jupiter.params.provider.MethodSource;").append(NL);
    }
    sb.append("import org.postgresql.fuzzkit.ScalarDecodeRobustnessModel;").append(NL);
    sb.append(NL);

    appendClassJavadoc(sb);
    sb.append("class ").append(CLASS_NAME).append(" {").append(NL);

    for (Target target : targets) {
      sb.append(NL);
      appendMethod(sb, target);
    }

    sb.append('}').append(NL);
    return sb.toString();
  }

  private static void appendClassJavadoc(StringBuilder sb) {
    sb.append("/**").append(NL)
        .append(" * GENERATED with {@link ScalarDecodeRobustnessFuzzTestGenerator} -- do not edit. Regenerate with"
            + " {@code ./gradlew :pgjdbc-jqf-test:generateJqfFuzzTargets}.").append(NL)
        .append(" *").append(NL)
        .append(" * <p>The JQF sibling of the Jazzer {@code GeneratedScalarDecodeRobustnessFuzzTest}: the"
            + " uniform scalar").append(NL)
        .append(" * decode-robustness family. For every built-in scalar leaf codec, one {@code @FuzzTest}"
            + " per readable").append(NL)
        .append(" * wire format feeds adversarial bytes to the decoder and asserts it either decodes or"
            + " refuses with a").append(NL)
        .append(" * {@code SQLException}, never leaking an unchecked exception. A binary-readable codec gets"
            + " a whole-array").append(NL)
        .append(" * {@code _binary} target and an offset-aware {@code _binaryOffset} sibling (the"
            + " {@code byte[] + off + len}").append(NL)
        .append(" * path); a text-readable one gets a {@code _text} target that decodes the raw bytes,"
            + " reaching invalid-UTF-8").append(NL)
        .append(" * wires. A guided parameter is a {@code byte[]} drawn by {@link PgValueArgumentsFactory}. A"
            + " single-byte").append(NL)
        .append(" * type ({@code \"char\"}) instead has a finite binary wire domain, so its binary targets are"
            + " exhaustive").append(NL)
        .append(" * {@code @ParameterizedTest}s over every wire. The targets, their names, and their disabled"
            + " state come").append(NL)
        .append(" * from {@link ScalarDecodeRobustnessModel}.").append(NL)
        .append(" */").append(NL);
  }

  private static void appendMethod(StringBuilder sb, Target target) {
    appendMethodJavadoc(sb, target);
    if (target.disabled()) {
      sb.append("  @Disabled(\"").append(escape(target.disabledReason())).append("\")").append(NL);
    }
    if (target.enumeratedByteDomain()) {
      appendEnumeratedMethod(sb, target);
      return;
    }
    sb.append("  @FuzzTest(arguments = PgValueArgumentsFactory.class)").append(NL);
    if (target.format() == Format.TEXT) {
      sb.append("  void ").append(target.methodName()).append("(byte[] payload) {").append(NL)
          .append("    CodecFuzzSupport.decodeScalarTextBytesExpectingNoLeak(payload, ")
          .append(target.oid()).append(");").append(NL);
    } else if (target.offsetVariant()) {
      sb.append("  void ").append(target.methodName()).append("(byte[] payload) {").append(NL)
          .append("    CodecFuzzSupport.decodeScalarBinarySliceExpectingNoLeak(payload, ")
          .append(target.oid()).append(");").append(NL);
    } else {
      sb.append("  void ").append(target.methodName()).append("(byte[] payload) {").append(NL)
          .append("    CodecFuzzSupport.decodeScalarBinaryExpectingNoLeak(payload, ")
          .append(target.oid()).append(");").append(NL);
    }
    sb.append("  }").append(NL);
  }

  // An enumerated target: its wire domain is finite, so it is an exhaustive @ParameterizedTest over
  // CodecFuzzSupport.singleByteBinaryDomain() rather than a guided @FuzzTest. Only binary targets are
  // enumerated, so both engines' generated classes stay identical for these methods.
  private static void appendEnumeratedMethod(StringBuilder sb, Target target) {
    String helper = target.offsetVariant()
        ? "decodeSingleByteBinarySlice" : "decodeSingleByteBinary";
    sb.append("  @ParameterizedTest").append(NL)
        .append("  @MethodSource(\"org.postgresql.fuzzkit.CodecFuzzSupport#singleByteBinaryDomain\")").append(NL)
        .append("  void ").append(target.methodName()).append("(byte[] data) {").append(NL)
        .append("    CodecFuzzSupport.").append(helper).append("(data, ")
        .append(target.oid()).append(");").append(NL)
        .append("  }").append(NL);
  }

  private static void appendMethodJavadoc(StringBuilder sb, Target target) {
    String subject = "{@code " + target.typeName() + "} (OID " + target.oid() + ")";
    String summary;
    if (target.enumeratedByteDomain()) {
      summary = "Exhaustive binary decode of " + subject
          + " over its whole single-byte wire domain" + (target.offsetVariant() ? " at a non-zero offset" : "")
          + "; every wire must decode without leaking, and the ASCII subset to its own character.";
    } else if (target.format() == Format.TEXT) {
      summary = "Adversarial text decode of " + subject
          + " from raw bytes; must not leak an unchecked exception.";
    } else if (target.offsetVariant()) {
      summary = "Adversarial binary decode of " + subject
          + " at a non-zero offset; must not leak unchecked.";
    } else {
      summary = "Adversarial binary decode of " + subject + "; must not leak an unchecked exception.";
    }
    sb.append("  /** ").append(summary).append(" */").append(NL);
  }

  // Escapes a reason string for a Java string literal in the emitted @Disabled annotation.
  private static String escape(String reason) {
    return reason.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
