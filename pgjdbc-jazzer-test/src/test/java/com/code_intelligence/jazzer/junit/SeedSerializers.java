/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.code_intelligence.jazzer.junit;

import java.lang.reflect.Method;

/**
 * A same-package accessor for Jazzer-JUnit's package-private {@code SeedSerializer}, so the seed-corpus
 * generator can serialise a seed with the <em>exact</em> writer the engine reads it back with, rather than
 * re-deriving the raw-{@code byte[]}-vs-mutation-framework split itself.
 *
 * <p>Jazzer-JUnit's {@code SeedArgumentsProvider} deserialises each corpus file with
 * {@code SeedSerializer.of(method).read(fileBytes)}; the matching writer is {@code .write(args)}. Both the
 * {@code SeedSerializer} interface and {@code SeedSerializer.of} are package-private to
 * {@code com.code_intelligence.jazzer.junit}, so this class lives in that package purely to reach them. It
 * adds no behaviour of its own -- it is a thin, typed bridge that keeps {@link
 * org.postgresql.test.jazzer.JazzerSeedCorpusGenerator} free of both reflection and a duplicated copy of
 * Jazzer's serializer-selection rule. jazzer-junit is a plain classpath jar (no {@code module-info}), so a
 * same-package helper compiled into this test source set is legal.
 */
public final class SeedSerializers {

  private SeedSerializers() {
  }

  /**
   * Serialises {@code args} into the on-disk seed-file bytes for {@code method}, using the same
   * {@code SeedSerializer} Jazzer-JUnit resolves for that signature, so the written bytes are the exact
   * inverse of what the engine reads back.
   *
   * @param method the {@code @FuzzTest} method whose seed is being written
   * @param args the seed argument values, one per method parameter
   * @return the seed file's bytes, in the layout the engine reads
   */
  public static byte[] write(Method method, Object[] args) {
    return SeedSerializer.of(method).write(args);
  }
}
