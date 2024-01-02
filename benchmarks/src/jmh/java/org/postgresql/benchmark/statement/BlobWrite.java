/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.statement;

import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.LargeObjectVacuum;

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

import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Fork(value = 1, jvmArgsPrepend = "-Xmx1g")
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class BlobWrite {
  @Param({"1024", "10240", "102400", "1048576", "10485760"})
  public int size;

  private Connection connection;

  private LargeObjectManager lom;

  private LargeObjectVacuum loVacuum;

  private byte[] buffer;

  private long lastLoId;

  @Setup
  public void setup() throws SQLException {
    connection = TestUtil.openDB();
    connection.setAutoCommit(false);
    loVacuum = new LargeObjectVacuum(connection);
    buffer = new byte[size];
    ThreadLocalRandom.current().nextBytes(buffer);
    lom = connection.unwrap(PGConnection.class).getLargeObjectAPI();
  }

  @Setup(Level.Invocation)
  public void prepareLob() throws SQLException {
    connection.commit();
    loVacuum.vacuum();
  }

  @TearDown(Level.Invocation)
  public void unlnkLo() throws SQLException {
    if (lastLoId != 0) {
      lom.unlink(lastLoId);
      lastLoId = 0;
    }
  }

  @Benchmark
  public void fromByteArray() throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement("select ?")) {
      ps.setBlob(1, new ByteArrayInputStream(buffer), buffer.length);
      ResultSet rs = ps.executeQuery();
      rs.next();
      lastLoId = rs.getLong(1);
    }
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(BlobWrite.class.getSimpleName())
        // .addProfiler("gc", "churn=false")
        // .addProfiler(FlightRecorderProfiler.class)
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
