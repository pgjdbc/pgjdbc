/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.util;

import org.postgresql.core.Oid;
import org.postgresql.util.internal.IntSet;

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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.roaringbitmap.RoaringBitmap;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Tests the performance of hex encoding. The results for OpenJDK 17.0.10 are as follows, so it
 * looks like {@link Character#forDigit(int, int)} is the way to go.
 * Here's a result for Apple M1 Max, Java 17.0.10, ARM64.
 * 23 is present in the set, 73 and 123456 are both absent.
 * <pre>
 * Benchmark                          (oid)  Mode  Cnt  Score   Error  Units
 * IntSetBenchmark.bitset_contains       23  avgt   15  0,990 ± 0,011  ns/op
 * IntSetBenchmark.bitset_contains       73  avgt   15  1,023 ± 0,049  ns/op
 * IntSetBenchmark.bitset_contains   123456  avgt   15  0,687 ± 0,008  ns/op
 * IntSetBenchmark.hashset_contains      23  avgt   15  2,040 ± 0,029  ns/op
 * IntSetBenchmark.hashset_contains      73  avgt   15  1,993 ± 0,017  ns/op
 * IntSetBenchmark.hashset_contains  123456  avgt   15  1,959 ± 0,025  ns/op
 * IntSetBenchmark.intset_contains       23  avgt   15  1,138 ± 0,030  ns/op
 * IntSetBenchmark.intset_contains       73  avgt   15  1,165 ± 0,013  ns/op
 * IntSetBenchmark.intset_contains   123456  avgt   15  0,653 ± 0,012  ns/op
 * IntSetBenchmark.roaring_contains      23  avgt   15  5,008 ± 0,045  ns/op
 * IntSetBenchmark.roaring_contains      73  avgt   15  5,557 ± 0,056  ns/op
 * IntSetBenchmark.roaring_contains  123456  avgt   15  1,165 ± 0,010  ns/op
 * </pre>
 */
@Fork(value = 3, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class IntSetBenchmark {
  /**
   * See {@code PgConnection#getSupportedBinaryOids()}.
   */
  private static final Collection<? extends Integer> SUPPORTED_BINARY_OIDS = Arrays.asList(
      Oid.BYTEA,
      Oid.INT2,
      Oid.INT4,
      Oid.INT8,
      Oid.FLOAT4,
      Oid.FLOAT8,
      Oid.NUMERIC,
      Oid.TIME,
      Oid.DATE,
      Oid.TIMETZ,
      Oid.TIMESTAMP,
      Oid.TIMESTAMPTZ,
      Oid.BYTEA_ARRAY,
      Oid.INT2_ARRAY,
      Oid.INT4_ARRAY,
      Oid.INT8_ARRAY,
      Oid.OID_ARRAY,
      Oid.FLOAT4_ARRAY,
      Oid.FLOAT8_ARRAY,
      Oid.VARCHAR_ARRAY,
      Oid.TEXT_ARRAY,
      Oid.POINT,
      Oid.BOX,
      Oid.UUID);

  IntSet intSet = new IntSet();
  BitSet bitSet = new BitSet();
  Set<Integer> hashSet = new HashSet<>();
  RoaringBitmap roaringBitmap = new RoaringBitmap();

  @Param({"23", "73", "123456"})
  int oid;

  @Setup
  public void setup() {
    intSet.addAll(SUPPORTED_BINARY_OIDS);
    hashSet.addAll(SUPPORTED_BINARY_OIDS);
    for (Integer oid : SUPPORTED_BINARY_OIDS) {
      bitSet.set(oid);
      roaringBitmap.add(oid);
    }
  }

  @Benchmark
  public boolean intset_contains() {
    return intSet.contains(oid);
  }

  @Benchmark
  public boolean bitset_contains() {
    return bitSet.get(oid);
  }

  @Benchmark
  public boolean hashset_contains() {
    return hashSet.contains(oid);
  }

  @Benchmark
  public boolean roaring_contains() {
    return roaringBitmap.contains(oid);
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(IntSetBenchmark.class.getSimpleName())
        //.addProfiler(GCProfiler.class)
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
