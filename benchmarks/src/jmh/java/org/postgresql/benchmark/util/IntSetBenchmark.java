/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.util;

import org.postgresql.core.Oid;
import org.postgresql.util.internal.IntSet;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
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
 * Benchmark                             (oid)  Mode  Cnt  Score   Error  Units
 * IntSetBenchmark.bitSet_contains          23  avgt   15  0,984 ± 0,009  ns/op
 * IntSetBenchmark.bitSet_contains          73  avgt   15  0,979 ± 0,005  ns/op
 * IntSetBenchmark.bitSet_contains      123456  avgt   15  0,688 ± 0,015  ns/op
 * IntSetBenchmark.hashSet_contains         23  avgt   15  2,026 ± 0,013  ns/op
 * IntSetBenchmark.hashSet_contains         73  avgt   15  1,985 ± 0,004  ns/op
 * IntSetBenchmark.hashSet_contains     123456  avgt   15  1,968 ± 0,032  ns/op
 * IntSetBenchmark.intOpenSet_contains      23  avgt   15  1,015 ± 0,011  ns/op
 * IntSetBenchmark.intOpenSet_contains      73  avgt   15  5,720 ± 0,596  ns/op
 * IntSetBenchmark.intOpenSet_contains  123456  avgt   15  8,430 ± 0,007  ns/op
 * IntSetBenchmark.intSet_contains          23  avgt   15  1,101 ± 0,009  ns/op
 * IntSetBenchmark.intSet_contains          73  avgt   15  1,117 ± 0,005  ns/op
 * IntSetBenchmark.intSet_contains      123456  avgt   15  0,693 ± 0,010  ns/op
 * IntSetBenchmark.roaring_contains         23  avgt   15  5,012 ± 0,044  ns/op
 * IntSetBenchmark.roaring_contains         73  avgt   15  5,561 ± 0,077  ns/op
 * IntSetBenchmark.roaring_contains     123456  avgt   15  1,163 ± 0,012  ns/op
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
  IntOpenHashSet intOpenHashSet = new IntOpenHashSet();

  @Param({"23", "73", "123456"})
  int oid;

  @Setup
  public void setup() {
    intSet.addAll(SUPPORTED_BINARY_OIDS);
    hashSet.addAll(SUPPORTED_BINARY_OIDS);
    for (Integer oid : SUPPORTED_BINARY_OIDS) {
      bitSet.set(oid);
      roaringBitmap.add(oid);
      intOpenHashSet.add((int) oid);
    }
  }

  @Benchmark
  public boolean intSet_contains() {
    return intSet.contains(oid);
  }

  @Benchmark
  public boolean bitSet_contains() {
    return bitSet.get(oid);
  }

  @Benchmark
  public boolean hashSet_contains() {
    return hashSet.contains(oid);
  }

  @Benchmark
  public boolean roaring_contains() {
    return roaringBitmap.contains(oid);
  }

  @Benchmark
  public boolean intOpenSet_contains() {
    return intOpenHashSet.contains(oid);
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
