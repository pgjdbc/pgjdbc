/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.statement;

import org.postgresql.PGProperty;
import org.postgresql.PGStatement;
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
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Measures the effect of {@code preparedStatementCacheTypeVariants} on a server-prepared
 * statement executed with a fixed parameter-type signature (the fast path, which must not
 * regress) and with alternating signatures (where {@code typeVariants=1} re-prepares on every
 * switch and a higher value keeps one statement per signature).
 */
@Fork(value = 3, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class TypeVariants {
  @Param({"1", "2"})
  private int typeVariants;

  private Connection connection;
  private PreparedStatement ps;
  private boolean stringTurn;
  private int cntr;

  @Setup(Level.Trial)
  public void setUp() throws SQLException {
    Properties props = new Properties();
    PGProperty.PREPARED_STATEMENT_CACHE_TYPE_VARIANTS.set(props, typeVariants);

    connection = TestUtil.openDB(props);
    connection.setAutoCommit(true);

    ps = connection.prepareStatement("SELECT /*TypeVariants*/ ?");
    ps.unwrap(PGStatement.class).setPrepareThreshold(1);
  }

  @TearDown(Level.Trial)
  public void tearDown() throws SQLException {
    ps.close();
    connection.close();
  }

  @Benchmark
  public void sameSignature(Blackhole b) throws SQLException {
    ps.setInt(1, cntr++);
    try (ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        b.consume(rs.getObject(1));
      }
    }
  }

  @Benchmark
  public void alternatingSignatures(Blackhole b) throws SQLException {
    stringTurn = !stringTurn;
    if (stringTurn) {
      ps.setString(1, Integer.toString(cntr++));
    } else {
      ps.setInt(1, cntr++);
    }
    try (ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        b.consume(rs.getObject(1));
      }
    }
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(TypeVariants.class.getSimpleName())
        .addProfiler(GCProfiler.class)
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
