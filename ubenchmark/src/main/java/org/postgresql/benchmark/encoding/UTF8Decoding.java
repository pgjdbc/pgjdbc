/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.encoding;

import org.postgresql.core.Encoding;

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

import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

/**
 * Tests the performance of UTF-8 decoding. UTF-8 is used a lot, so we need to know the performance
 */
@Fork(value = 1, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class UTF8Decoding {

  @Param({"1", "5", "10", "50", "100"})
  public int length;

  private byte[] sourceByte;
  private static final Charset UTF_8 = Charset.forName("UTF-8");

  @Setup
  public void setup() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < length; i++) {
      sb.append("Привет мир, ");
      sb.append("こんにちは世界, ");
      sb.append("Hola mundo, ");
    }
    sourceByte = sb.toString().getBytes(UTF_8);
  }

  @Benchmark
  public String decodeUTF8Encoding() throws java.io.IOException {
    Encoding enc = Encoding.getJVMEncoding("UTF-8");
    return enc.decode(sourceByte);
  }

  @Benchmark
  public String decodeUTF8String() throws java.io.UnsupportedEncodingException {
    return new String(sourceByte, "UTF-8");
  }

  @Benchmark
  public String decodeUTF8Charset() {
    return new String(sourceByte, UTF_8);
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(UTF8Decoding.class.getSimpleName())
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
