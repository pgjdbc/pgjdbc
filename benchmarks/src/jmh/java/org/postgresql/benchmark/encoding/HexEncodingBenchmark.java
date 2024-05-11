/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.encoding;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Tests the performance of hex encoding.
 * The results for OpenJDK 17.0.10 are as follows, so it looks like
 * {@link Character#forDigit(int, int)} is the way to go.
 * <pre>
 * Benchmark                        (length)  Mode  Cnt    Score   Error  Units
 * HexEncoding.character_fordigit        100  avgt   15  251,683 ± 0,292  ns/op
 * HexEncoding.digit1_array_lookup       100  avgt   15  264,285 ± 0,215  ns/op
 * HexEncoding.digit1_branchless         100  avgt   15  304,102 ± 0,361  ns/op
 * HexEncoding.digit1_conditional        100  avgt   15  252,811 ± 1,458  ns/op
 * HexEncoding.digit2_char_array         100  avgt   15  291,056 ± 2,524  ns/op
 * HexEncoding.digit2_string_array       100  avgt   15  453,326 ± 5,742  ns/op
 * HexEncoding.digit2_subarray           100  avgt   15  330,701 ± 2,523  ns/op
 * </pre>
 */
@Fork(value = 3, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class HexEncodingBenchmark {

  @Param({"100"})//{"1", "5", "10", "50", "100"})
  public int length;

  byte[] data;
  StringBuilder sb = new StringBuilder();

  private static final char[] HEX_DIGITS = "01234567890abcdef".toCharArray();
  private static final String[] HEX_PAIRS = new String[256];
  private static final char[][] HEX_CHAR_PAIRS = new char[256][];
  private static final char[] HEX_CHAR_BIG_ARRAY;

  static {
    for (int i = 0; i < HEX_PAIRS.length; i++) {
      HEX_PAIRS[i] = String.format("%02x", i);
      HEX_CHAR_PAIRS[i] = HEX_PAIRS[i].toCharArray();
    }
    HEX_CHAR_BIG_ARRAY = String.join("", HEX_PAIRS).toCharArray();
  }

  @Setup
  public void setup() {
    data = new byte[length];
    sb.ensureCapacity(length * 2);
    ThreadLocalRandom.current().nextBytes(data);
  }

  @Setup(Level.Invocation)
  public void clearBuffer() {
    sb.setLength(0);
  }

  @Benchmark
  public StringBuilder digit1_array_lookup() {
    StringBuilder sb = this.sb;
    char[] hexDigits = HEX_DIGITS;
    for (byte b : data) {
      sb.append(hexDigits[(b >> 4) & 0xF]);
      sb.append(hexDigits[b & 0xF]);
    }
    return sb;
  }

  @Benchmark
  public StringBuilder character_fordigit() {
    StringBuilder sb = this.sb;
    for (byte b : data) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb;
  }

  private static char digit_branchless(int digit) {
    return (char) (87 + digit + (((digit - 10) >> 31) & -39));
  }

  @Benchmark
  public StringBuilder digit1_branchless() {
    StringBuilder sb = this.sb;
    for (byte b : data) {
      sb.append(digit_branchless((b >> 4) & 0xF));
      sb.append(digit_branchless(b & 0xF));
    }
    return sb;
  }

  private static char digit_conditional(int digit) {
    if (digit < 10) {
      return (char) ('0' + digit);
    }
    return (char) ('a' - 10 + digit);
  }

  @Benchmark
  public StringBuilder digit1_conditional() {
    StringBuilder sb = this.sb;
    for (byte b : data) {
      sb.append(digit_conditional((b >> 4) & 0xF));
      sb.append(digit_conditional(b & 0xF));
    }
    return sb;
  }

  @Benchmark
  public StringBuilder digit2_string_array() {
    StringBuilder sb = this.sb;
    for (byte b : data) {
      sb.append(HEX_PAIRS[b & 0xff]);
    }
    return sb;
  }

  @Benchmark
  public StringBuilder digit2_char_array() {
    StringBuilder sb = this.sb;
    for (byte b : data) {
      sb.append(HEX_CHAR_PAIRS[b & 0xff]);
    }
    return sb;
  }

  @Benchmark
  public StringBuilder digit2_subarray() {
    StringBuilder sb = this.sb;
    for (byte b : data) {
      int idx = (b & 0xff) * 2;
      sb.append(HEX_CHAR_BIG_ARRAY, idx, 2);
    }
    return sb;
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(HexEncodingBenchmark.class.getSimpleName())
        //.addProfiler(GCProfiler.class)
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
