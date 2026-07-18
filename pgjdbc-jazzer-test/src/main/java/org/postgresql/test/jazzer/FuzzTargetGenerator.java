/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jazzer;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The single generator entry point for this module's {@code @FuzzTest} source: it runs each
 * {@code *FuzzTestGenerator} in turn, so the Gradle {@code generateJazzerFuzzTargets} task points at this
 * one {@code main} and never has to change as more generators are added.
 *
 * <p>Each generator is named after the {@code Generated*FuzzTest} class it emits, so a target class is easy
 * to trace back to its generator. Add a new family by writing a {@code <Name>FuzzTestGenerator} with a
 * {@code generate(Path)} method and calling it below.
 *
 * <p>Invoked from Gradle as a {@code JavaExec}; the single argument is the generated-sources root to write
 * into. It needs no database connection: every generator builds from the offline codec registry.
 */
public final class FuzzTargetGenerator {

  private FuzzTargetGenerator() {
  }

  /**
   * Runs every source generator under the directory given as the single argument.
   *
   * @param args {@code args[0]} is the generated-sources root (a {@code java} source directory) to write into
   * @throws IOException if a generated file cannot be written
   */
  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      throw new IllegalArgumentException(
          "usage: FuzzTargetGenerator <generated-sources-root>; got " + args.length + " arguments");
    }
    Path generatedSourcesRoot = Paths.get(args[0]);
    ScalarDecodeRobustnessFuzzTestGenerator.generate(generatedSourcesRoot);
    PrimitiveCapabilityFuzzTestGenerator.generate(generatedSourcesRoot);
    CoercionReaderFuzzTestGenerator.generate(generatedSourcesRoot);
  }
}
