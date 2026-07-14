/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jazzer;

import org.postgresql.api.codec.Format;
import org.postgresql.fuzzkit.ScalarDecodeRobustnessModel;
import org.postgresql.fuzzkit.ScalarDecodeRobustnessModel.Target;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Emits {@code GeneratedScalarDecodeRobustnessFuzzTest.java} from {@link ScalarDecodeRobustnessModel}, so the
 * uniform scalar decode-robustness targets are generated from the codec registry rather than hand-kept.
 *
 * <p>Jazzer-JUnit fuzzes only real, physically compiled {@code @FuzzTest} methods and resolves each one's
 * seed corpus from a classpath resource named after the method, so the targets must be concrete methods
 * with stable names. This generator writes one such source file; the Gradle task compiles it into the test
 * source set. Output is deterministic -- OID-ordered, no timestamps -- so a rerun produces identical bytes.
 *
 * <p>Driven by {@link FuzzTargetGenerator}, the module's single generator entry point, which passes the
 * generated-sources root. It needs no database connection: the model is built from the offline codec registry.
 */
public final class ScalarDecodeRobustnessFuzzTestGenerator {

  private static final String PACKAGE = "org.postgresql.test.jazzer";
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
    for (Target target : targets) {
      if (target.disabled()) {
        anyDisabled = true;
        break;
      }
    }

    StringBuilder sb = new StringBuilder(8192);
    sb.append("/*").append(NL)
        .append(" * Copyright (c) 2026, PostgreSQL Global Development Group").append(NL)
        .append(" * See the LICENSE file in the project root for more information.").append(NL)
        .append(" */").append(NL).append(NL)
        .append("package ").append(PACKAGE).append(';').append(NL).append(NL);

    sb.append("import org.postgresql.fuzzkit.CodecFuzzSupport;").append(NL).append(NL)
        .append("import com.code_intelligence.jazzer.junit.FuzzTest;").append(NL)
        .append("import com.code_intelligence.jazzer.mutation.annotation.NotNull;").append(NL);
    if (anyDisabled) {
      sb.append("import org.junit.jupiter.api.Disabled;").append(NL);
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
            + " {@code ./gradlew :pgjdbc-jazzer-test:generateJazzerFuzzTargets}.").append(NL)
        .append(" *").append(NL)
        .append(" * <p>The uniform scalar decode-robustness family: for every built-in scalar leaf codec,"
            + " one").append(NL)
        .append(" * {@code @FuzzTest} per readable wire format that feeds adversarial bytes to the decoder"
            + " and").append(NL)
        .append(" * asserts it either decodes or refuses with a {@code SQLException}, never leaking an"
            + " unchecked").append(NL)
        .append(" * exception. A binary-readable codec gets a whole-array {@code _binary} target and an"
            + " offset-aware").append(NL)
        .append(" * {@code _binaryOffset} sibling (the {@code byte[] + off + len} path); a text-readable one"
            + " gets a").append(NL)
        .append(" * {@code _text} target. The targets, their names, and their disabled state come from"
            + " {@link ScalarDecodeRobustnessModel}.").append(NL)
        .append(" */").append(NL);
  }

  private static void appendMethod(StringBuilder sb, Target target) {
    appendMethodJavadoc(sb, target);
    if (target.disabled()) {
      sb.append("  @Disabled(\"").append(escape(target.disabledReason())).append("\")").append(NL);
    }
    sb.append("  @FuzzTest").append(NL);
    if (target.format() == Format.TEXT) {
      sb.append("  void ").append(target.methodName()).append("(@NotNull String literal) {").append(NL)
          .append("    CodecFuzzSupport.decodeScalarTextExpectingNoLeak(literal, ")
          .append(target.oid()).append(");").append(NL);
    } else if (target.offsetVariant()) {
      sb.append("  void ").append(target.methodName()).append("(byte @NotNull [] data) {").append(NL)
          .append("    CodecFuzzSupport.decodeScalarBinarySliceExpectingNoLeak(data, ")
          .append(target.oid()).append(");").append(NL);
    } else {
      sb.append("  void ").append(target.methodName()).append("(byte @NotNull [] data) {").append(NL)
          .append("    CodecFuzzSupport.decodeScalarBinaryExpectingNoLeak(data, ")
          .append(target.oid()).append(");").append(NL);
    }
    sb.append("  }").append(NL);
  }

  private static void appendMethodJavadoc(StringBuilder sb, Target target) {
    String subject = "{@code " + target.typeName() + "} (OID " + target.oid() + ")";
    String summary;
    if (target.format() == Format.TEXT) {
      summary = "Adversarial text decode of " + subject + "; must not leak an unchecked exception.";
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
