/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.stream;

import org.postgresql.benchmark.util.IntParser;
import org.postgresql.core.PGStream;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.VisibleBufferedInputStream;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.test.TestUtil;

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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.concurrent.TimeUnit;

@Fork(value = 5, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class StreamReceiveInteger {

  private Connection connection;

  private PGStream pgStream;

  @Param({"4096", "8192", "65536", "131072", "1048576"})
  private int byteArrSize;

  private byte[] integer2Data;

  private byte[] integer4Data;

  @Setup
  public void setUp() throws Exception {
    connection = TestUtil.openDB();

    QueryExecutor queryExecutor = ((PgConnection) connection).getQueryExecutor();

    Field pgStreamField = queryExecutor.getClass().getSuperclass().getDeclaredField("pgStream");
    pgStreamField.setAccessible(true);
    pgStream = (PGStream) pgStreamField.get(queryExecutor);

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

  @TearDown
  public void tearDown() throws Exception {
    TestUtil.closeDB(connection);
  }

  @Benchmark
  public void receiveInteger4Benchmark(Blackhole bh) throws Exception {
    replaceInputStream(integer4Data);
    while (hasMoreData(4)) {
      int value = pgStream.receiveInteger4();
      bh.consume(value);
    }
  }

  @Benchmark
  public void receiveInteger2Benchmark(Blackhole bh) throws Exception {
    replaceInputStream(integer2Data);
    while (hasMoreData(2)) {
      int value = pgStream.receiveInteger2();
      bh.consume(value);
    }
  }

  private void replaceInputStream(byte[] data) throws Exception {
    VisibleBufferedInputStream newInputStream = new VisibleBufferedInputStream(new ByteArrayInputStream(data), 8192);
    Field pgInputStreamField = pgStream.getClass().getDeclaredField("pgInput");
    pgInputStreamField.setAccessible(true);
    pgInputStreamField.set(pgStream, newInputStream);
  }

  private boolean hasMoreData(int bytesNeeded) throws Exception {
    Field pgInputStreamField = pgStream.getClass().getDeclaredField("pgInput");
    pgInputStreamField.setAccessible(true);
    VisibleBufferedInputStream pgInputStream = (VisibleBufferedInputStream) pgInputStreamField.get(pgStream);
    return pgInputStream.available() >= bytesNeeded;
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(IntParser.class.getSimpleName())
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
