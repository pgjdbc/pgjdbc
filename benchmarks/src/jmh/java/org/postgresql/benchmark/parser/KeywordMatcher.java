/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.parser;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Compares a keyword matcher written out character by character with one that loops over the
 * keyword string, as {@code Parser.parseKeyword} does. Both copies are local, so one run measures
 * both.
 */
@Fork(value = 3)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class KeywordMatcher {
  @Param({"select", "selecz", "zelect"})
  private String word;

  private char[] query;

  @Setup
  public void setup() {
    query = word.toCharArray();
  }

  @Benchmark
  public boolean unrolled() {
    return parseSelectUnrolled(query, 0);
  }

  @Benchmark
  public boolean loop() {
    return parseKeyword(query, 0, "select");
  }

  private static boolean parseSelectUnrolled(final char[] query, int offset) {
    if (query.length < (offset + 6)) {
      return false;
    }

    return (query[offset] | 32) == 's'
        && (query[offset + 1] | 32) == 'e'
        && (query[offset + 2] | 32) == 'l'
        && (query[offset + 3] | 32) == 'e'
        && (query[offset + 4] | 32) == 'c'
        && (query[offset + 5] | 32) == 't';
  }

  private static boolean parseKeyword(char[] query, int offset, String keyword) {
    if (query.length < offset + keyword.length()) {
      return false;
    }
    for (int i = 0; i < keyword.length(); i++) {
      if ((query[offset + i] | 32) != keyword.charAt(i)) {
        return false;
      }
    }
    return true;
  }
}
