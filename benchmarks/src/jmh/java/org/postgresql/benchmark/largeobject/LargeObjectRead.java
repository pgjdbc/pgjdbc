/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.largeobject;

import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.StrangeInputStream;

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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Fork(value = 1, jvmArgsPrepend = "-Xmx1g")
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class LargeObjectRead {
  @Param({"100", "1024", "102400", "1048576"})
  public int size;

  @Param({"1", "10", "100", "1024", "10240", "65536", "131072", "524288"})
  public int readSize;

  @Param({"4096", "8192", "65536", "131072"})
  public int bufferSize;

  private Connection connection;

  private LargeObjectManager lom;

  private long loId;

  private LargeObject lo;

  private byte[] input;
  private byte[] output;
  @SuppressWarnings("unused")
  private byte[] buffer;

  @Setup
  public void setup() throws SQLException {
    connection = TestUtil.openDB();
    connection.setAutoCommit(false);
    lom = connection.unwrap(PGConnection.class).getLargeObjectAPI();
    input = new byte[size];
    output = new byte[size];
    ThreadLocalRandom.current().nextBytes(input);
  }

  @Setup(Level.Invocation)
  public void prepareLob() throws SQLException, IOException {
    loId = lom.createLO();
    lo = lom.open(loId);
    OutputStream os = lo.getOutputStream();
    os.write(input);
    os.close();
    lo.close();
    lo = lom.open(loId);
  }

  @TearDown(Level.Invocation)
  public void cleanupLob() throws SQLException {
    lo.close();
    lom.unlink(loId);
    if (!Arrays.equals(input, output)) {
      throw new IllegalStateException(
          "Input and output values do not match");
    }
  }

  @TearDown
  public void tearDown() throws SQLException {
  }

  @Benchmark
  public void readByte() throws SQLException, IOException {
    if (readSize != 1) {
      throw new IllegalArgumentException();
    }
    InputStream is = getInputStream();
    byte[] output = this.output;
    for (int i = 0; i < output.length; i++) {
      int value = is.read();
      if (value == -1) {
        throw new EOFException("Unexpected end of stream: there should be at least " + (output.length - i) + " bytes left");
      }
      output[i] = (byte) value;
    }
  }

  private InputStream getInputStream() throws SQLException {
    return bufferSize == 0 ? lo.getInputStream() : lo.getInputStream(bufferSize, Long.MAX_VALUE);
  }

  @Benchmark
  public void readArray() throws SQLException, IOException {
    readBuffer(getInputStream());
  }

  @Benchmark
  public void readArrayRandom() throws SQLException, IOException {
    readBuffer(
        new StrangeInputStream(
            ThreadLocalRandom.current().nextLong(),
            getInputStream()
        ));
  }

  private void readBuffer(InputStream is) throws IOException {
    if (readSize > size) {
      throw new IllegalArgumentException();
    }
    if (readSize > bufferSize && bufferSize != 4096) {
      // In fact, when read size exceeds buffer size we should not depend on the buffer size,
      // however, we verify 4096 buffer anyway to ensure the performance is not affected by
      // buffer size
      throw new IllegalArgumentException();
    }
    byte[] output = this.output;
    int offs = 0;
    int len = output.length;
    while (len > 0) {
      int readSize = Math.min(len, this.readSize);
      int read = is.read(output, offs, readSize);
      if (read == -1) {
        throw new EOFException(
            "Unexpected end of stream: there should be at least " + len + " " + "bytes left");
      }
      offs += read;
      len -= read;
    }
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(LargeObjectRead.class.getSimpleName())
        // .addProfiler("gc", "churn=false")
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
