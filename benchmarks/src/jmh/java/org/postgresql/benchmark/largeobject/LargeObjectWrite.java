/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.largeobject;

import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.LargeObjectVacuum;
import org.postgresql.test.util.StrangeOutputStream;

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

import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Fork(value = 3, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class LargeObjectWrite {
  @Param({"100", "1024", "102400", "1048576", "10485760"})
  public int size;

  @Param({"1", "1000", "10240", "65536", "131072", "524288"})
  public int writeSize;

  private Connection connection;

  private LargeObjectManager lom;

  private long loId;

  private LargeObject lo;

  private LargeObjectVacuum loVacuum;

  private byte[] buffer;

  @Setup
  public void setup() throws SQLException {
    connection = TestUtil.openDB();
    connection.setAutoCommit(false);
    lom = connection.unwrap(PGConnection.class).getLargeObjectAPI();
    loVacuum = new LargeObjectVacuum(connection);
    buffer = new byte[size];
    ThreadLocalRandom.current().nextBytes(buffer);
  }

  @Setup(Level.Invocation)
  public void prepareLob() throws SQLException {
    connection.commit();
    loVacuum.vacuum();
    loId = lom.createLO();
    lo = lom.open(loId);
  }

  @TearDown(Level.Invocation)
  public void cleanupLob() throws SQLException {
    lo.close();
    lom.unlink(loId);
  }

  @TearDown
  public void tearDown() throws SQLException {
  }

  @Benchmark
  public void writeByte() throws SQLException, IOException {
    if (writeSize != 1) {
      throw new IllegalArgumentException();
    }
    OutputStream os = lo.getOutputStream();
    byte[] buffer = this.buffer;
    for (byte b : buffer) {
      os.write(b);
    }
    os.flush();
  }

  @Benchmark
  public void writeArray() throws SQLException, IOException {
    OutputStream os = lo.getOutputStream();

    writeBuffer(os);
  }

  @Benchmark
  public void writeArrayRandom() throws SQLException, IOException {
    OutputStream os =
        new StrangeOutputStream(
            lo.getOutputStream(),
            ThreadLocalRandom.current().nextLong(),
            0.0);

    writeBuffer(os);
  }

  private void writeBuffer(OutputStream os) throws IOException {
    if (writeSize > size) {
      throw new IllegalArgumentException();
    }
    byte[] buffer = this.buffer;
    int offs = 0;
    int len = buffer.length;
    while (len > 0) {
      int writeSize = Math.min(len, this.writeSize);
      os.write(buffer, offs, writeSize);
      offs += writeSize;
      len -= writeSize;
    }
    os.flush();
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(LargeObjectWrite.class.getSimpleName())
        // .addProfiler("gc", "churn=false")
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
