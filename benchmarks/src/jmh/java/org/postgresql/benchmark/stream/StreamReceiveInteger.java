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

  private byte[] integerData;

  @Setup
  public void setUp() throws Exception {
    // we only use a fixed buffer size of 8192 (8 KB)
    integerData = new byte[8192];

    for (int i = 0; i < integerData.length; i += 2) {
      int value = i / 2;
      integerData[i] = (byte) (value >> 8);
      integerData[i + 1] = (byte) (value & 0xFF);
    }
  }

  @Benchmark
  public void readInt4Benchmark(Blackhole bh) throws Exception {
    // We can't reuse the stream, so we need to create it in a benchmark method
    VisibleBufferedInputStream bufferedInputStream =
        new VisibleBufferedInputStream(
            new ByteArrayInputStream(integerData), integerData.length);
    for (int i = 0; i < integerData.length / 4; i++) {
      int value = bufferedInputStream.readInt4();
      bh.consume(value);
    }
  }

  @Benchmark
  public void readInt2Benchmark(Blackhole bh) throws Exception {
    // We can't reuse the stream, so we need to create it in a benchmark method
    VisibleBufferedInputStream bufferedInputStream =
        new VisibleBufferedInputStream(
            new ByteArrayInputStream(integerData), integerData.length);
    for (int i = 0; i < integerData.length / 2; i++) {
      int value = bufferedInputStream.readInt2();
      bh.consume(value);
    }
  }

  @Benchmark
  public void readInt1Benchmark(Blackhole bh) throws Exception {
    // We can't reuse the stream, so we need to create it in a benchmark method
    VisibleBufferedInputStream bufferedInputStream =
        new VisibleBufferedInputStream(
            new ByteArrayInputStream(integerData), integerData.length);
    for (int i = 0; i < integerData.length; i++) {
      int value = bufferedInputStream.read();
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
