/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.largeobject;

import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.test.TestUtil;

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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Compares the two ways of reading a large object: {@link LargeObject#read(byte[], int, int)},
 * which receives the data into the caller's buffer, and {@link LargeObject#read(int)}, which
 * returns a fresh array per chunk.
 *
 * <p>Run with {@code -prof gc} and read {@code gc.alloc.rate.norm}. The wall-clock score is
 * dominated by round-trip jitter, and the difference between the two methods does not survive it.
 * Reading through a stream instead is covered by {@link LargeObjectRead}.</p>
 */
@Fork(value = 1, jvmArgsPrepend = "-Xmx1g")
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class BlobRead {
  @Param({"102400", "1048576", "10485760"})
  public int size;

  @Param({"65536"})
  public int chunkSize;

  private Connection connection;

  private LargeObjectManager lom;

  private long loId;

  private LargeObject lo;

  private byte[] readBuffer;

  @Setup
  public void setup() throws SQLException {
    connection = TestUtil.openDB();
    connection.setAutoCommit(false);
    lom = connection.unwrap(PGConnection.class).getLargeObjectAPI();
    byte[] data = new byte[size];
    ThreadLocalRandom.current().nextBytes(data);
    loId = lom.createLO();
    try (LargeObject writer = lom.open(loId, LargeObjectManager.WRITE)) {
      writer.write(data);
    }
    connection.commit();
    // The object stays open for the whole run, so lo_open and lo_close do not land in the
    // measurement: at 100 KB the reads are three round trips, and opening and closing would add
    // two more
    lo = lom.open(loId, LargeObjectManager.READ);
    readBuffer = new byte[chunkSize];
  }

  @Setup(Level.Invocation)
  public void rewind() throws SQLException {
    lo.seek(0);
  }

  @TearDown
  public void tearDown() throws SQLException {
    lo.close();
    lom.unlink(loId);
    connection.commit();
    connection.close();
  }

  @Benchmark
  public long readIntoUserBuffer() throws SQLException {
    long total = 0;
    int read;
    while ((read = lo.read(readBuffer, 0, readBuffer.length)) > 0) {
      total += read;
    }
    return total;
  }

  @Benchmark
  public long readAllocatingApi() throws SQLException {
    // LargeObject.read(int) returns a fresh array per chunk; AbstractBlobClob.getBytes reads that
    // way. The copy stands for a caller that needs the data in a buffer of its own, so that both
    // benchmarks do the same work with it.
    long total = 0;
    while (true) {
      byte[] chunk = lo.read(readBuffer.length);
      if (chunk.length == 0) {
        break;
      }
      System.arraycopy(chunk, 0, readBuffer, 0, chunk.length);
      total += chunk.length;
    }
    return total;
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(BlobRead.class.getSimpleName())
        .addProfiler("gc")
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
