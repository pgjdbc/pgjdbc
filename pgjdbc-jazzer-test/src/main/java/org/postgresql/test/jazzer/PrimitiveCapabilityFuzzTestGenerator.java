/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jazzer;

import org.postgresql.fuzzkit.PrimitiveCapabilityModel;
import org.postgresql.fuzzkit.PrimitiveCapabilityModel.Target;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Emits {@code GeneratedPrimitiveCapabilityFuzzTest.java} from {@link PrimitiveCapabilityModel}, so the
 * primitive-capability parity targets are generated from the shared model rather than hand-kept in lockstep
 * with the JQF sibling.
 *
 * <p>Jazzer's mutators map the primitive arguments straight from each {@code @FuzzTest} signature, so a
 * range-checked codec sees values well outside its range and the "too large a value throws on both paths"
 * property is exercised. The generated methods are concrete, stably-named, and physically compiled, which is
 * what Jazzer-JUnit fuzzes; they carry no seed corpus (a primitive argument has no reverse-encoded seed), so
 * in regression mode the JUnit engine replays the empty input.
 *
 * <p>Driven by {@link FuzzTargetGenerator}, the module's single generator entry point, which passes the
 * generated-sources root. It needs no database connection: the model is a fixed hand-authored family.
 */
public final class PrimitiveCapabilityFuzzTestGenerator {

  private static final String PACKAGE = "org.postgresql.test.jazzer";
  private static final String CLASS_NAME = "GeneratedPrimitiveCapabilityFuzzTest";
  private static final String NL = "\n";

  private PrimitiveCapabilityFuzzTestGenerator() {
  }

  /**
   * Generates {@code GeneratedPrimitiveCapabilityFuzzTest.java} under {@code generatedSourcesRoot}.
   *
   * @param generatedSourcesRoot the generated-sources root (a {@code java} source directory) to write into
   * @throws IOException if the file cannot be written
   */
  public static void generate(Path generatedSourcesRoot) throws IOException {
    List<Target> targets = PrimitiveCapabilityModel.targets();
    String source = render(targets);

    Path packageDir = generatedSourcesRoot.resolve(PACKAGE.replace('.', '/'));
    Files.createDirectories(packageDir);
    Path file = packageDir.resolve(CLASS_NAME + ".java");
    Files.write(file, source.getBytes(StandardCharsets.UTF_8));
    System.out.println("PrimitiveCapabilityFuzzTestGenerator: wrote " + targets.size() + " @FuzzTest targets to "
        + file);
  }

  /** Renders the whole source file for {@code targets}. */
  private static String render(List<Target> targets) {
    StringBuilder sb = new StringBuilder(8192);
    sb.append("/*").append(NL)
        .append(" * Copyright (c) 2026, PostgreSQL Global Development Group").append(NL)
        .append(" * See the LICENSE file in the project root for more information.").append(NL)
        .append(" */").append(NL).append(NL)
        .append("package ").append(PACKAGE).append(';').append(NL).append(NL);

    sb.append("import org.postgresql.core.Oid;").append(NL)
        .append("import org.postgresql.fuzzkit.CodecFuzzSupport;").append(NL)
        .append("import org.postgresql.fuzzkit.PrimitiveCapabilityModel;").append(NL).append(NL)
        .append("import com.code_intelligence.jazzer.junit.FuzzTest;").append(NL).append(NL)
        .append("import java.sql.SQLException;").append(NL).append(NL);

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
        .append(" * GENERATED with {@link PrimitiveCapabilityFuzzTestGenerator} -- do not edit. Regenerate with"
            + " {@code ./gradlew :pgjdbc-jazzer-test:generateJazzerFuzzTargets}.").append(NL)
        .append(" *").append(NL)
        .append(" * <p>Outcome-parity properties for the opt-in primitive-capability interfaces: each no-box"
            + " override").append(NL)
        .append(" * has the same outcome as its boxing default -- the same bytes/value on success and the same"
            + " failure").append(NL)
        .append(" * (same SQLState) on a bad input. Jazzer maps each primitive argument straight from the"
            + " signature, so").append(NL)
        .append(" * a range-checked codec sees out-of-range values and the overflow property has teeth. The"
            + " JQF sibling").append(NL)
        .append(" * {@code GeneratedPrimitiveCapabilityFuzzTest} in pgjdbc-jqf-test drives the same targets"
            + " from the same").append(NL)
        .append(" * {@link PrimitiveCapabilityModel}.").append(NL)
        .append(" */").append(NL);
  }

  private static void appendMethod(StringBuilder sb, Target target) {
    if (!target.precedingComment().isEmpty()) {
      for (String comment : target.precedingComment()) {
        sb.append("  ").append(comment).append(NL);
      }
      // A blank line separates a section header from the method it introduces, as in the hand-written source.
      sb.append(NL);
    }
    sb.append("  @FuzzTest").append(NL);
    sb.append("  void ").append(target.methodName()).append('(').append(target.parameterType())
        .append(" value) throws SQLException {").append(NL);
    for (String line : target.body()) {
      sb.append("    ").append(line).append(NL);
    }
    sb.append("  }").append(NL);
  }
}
