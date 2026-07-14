/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.fuzzkit.PrimitiveCapabilityModel;
import org.postgresql.fuzzkit.PrimitiveCapabilityModel.Target;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Emits {@code GeneratedPrimitiveCapabilityFuzzTest.java} from {@link PrimitiveCapabilityModel}, the JQF
 * sibling of the Jazzer {@code GeneratedPrimitiveCapabilityFuzzTest}. Both engines generate the
 * primitive-capability parity targets from the shared model rather than hand-keeping them in lockstep, so
 * they drive the same properties and differ only in the engine that supplies the primitive argument.
 *
 * <p>Each range-checked codec is fed its wider type -- an {@code int} to {@code int2}, a {@code long} to
 * {@code int4} -- so the overflow path is generated and the "too large a value throws on both paths"
 * property has teeth. The generated methods take a bare primitive, which JQF's jetCheck provider supplies
 * through the ServiceLoader; they need no {@code arguments = ...} factory.
 *
 * <p>Driven by {@link FuzzTargetGenerator}, the module's single generator entry point, which passes the
 * generated-sources root. It needs no database connection: the model is a fixed hand-authored family.
 */
public final class PrimitiveCapabilityFuzzTestGenerator {

  private static final String PACKAGE = "org.postgresql.test.fuzz";
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
        .append("import edu.berkeley.cs.jqf.junit5.FuzzTest;").append(NL).append(NL)
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
            + " {@code ./gradlew :pgjdbc-jqf-test:generateJqfFuzzTargets}.").append(NL)
        .append(" *").append(NL)
        .append(" * <p>The JQF sibling of the Jazzer {@code GeneratedPrimitiveCapabilityFuzzTest}:"
            + " outcome-parity").append(NL)
        .append(" * properties for the opt-in primitive-capability interfaces. Each no-box override has the"
            + " same outcome").append(NL)
        .append(" * as its boxing default -- the same bytes/value on success and the same failure (same"
            + " SQLState) on a").append(NL)
        .append(" * bad input. Each range-checked codec is fed its wider type so the overflow property has"
            + " teeth. The").append(NL)
        .append(" * targets come from the shared {@link PrimitiveCapabilityModel}.").append(NL)
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
