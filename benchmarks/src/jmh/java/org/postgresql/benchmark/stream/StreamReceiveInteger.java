/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.stream;

import org.postgresql.core.VisibleBufferedInputStream;

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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.ByteArrayInputStream;
import java.util.concurrent.TimeUnit;

@Fork(value = 5, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class StreamReceiveInteger {

  private VisibleBufferedInputStream bufferedInputStream;
  @Param({"4096", "8192", "65536", "131072", "1048576"})
  private int byteArrSize;
  private byte[] integer2Data;
  private byte[] integer4Data;

  @Setup
  public void setUp() throws Exception {
    integer2Data = new byte[byteArrSize];
    integer4Data = new byte[byteArrSize];

    for (int i = 0; i < integer2Data.length; i += 2) {
      int value = i / 2;
      integer2Data[i] = (byte) (value >> 8);
      integer2Data[i + 1] = (byte) (value & 0xFF);
    }

    for (int i = 0; i < integer4Data.length; i += 4) {
      int value = i / 4;
      integer4Data[i] = (byte) (value >> 24);
      integer4Data[i + 1] = (byte) ((value >> 16));
      integer4Data[i + 2] = (byte) ((value >> 8));
      integer4Data[i + 3] = (byte) (value & 0xFF);
    }
  }

  @Benchmark
  public void receiveInteger4Benchmark(Blackhole bh) throws Exception {
    bufferedInputStream = new VisibleBufferedInputStream(new ByteArrayInputStream(integer4Data), byteArrSize);

    while (bufferedInputStream.available() >= 4) {
      int value = bufferedInputStream.receiveInteger4();
      bh.consume(value);
    }
  }

  @Benchmark
  public void receiveInteger2Benchmark(Blackhole bh) throws Exception {
    bufferedInputStream = new VisibleBufferedInputStream(new ByteArrayInputStream(integer2Data), byteArrSize);

    while (bufferedInputStream.available() >= 2) {
      int value = bufferedInputStream.receiveInteger2();
      bh.consume(value);
    }
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(StreamReceiveInteger.class.getSimpleName())
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
