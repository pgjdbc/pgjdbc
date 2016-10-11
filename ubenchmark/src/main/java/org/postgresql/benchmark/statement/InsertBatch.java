/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.statement;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.postgresql.util.ConnectionUtil;

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
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.CharArrayWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Fork(value = 1, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class InsertBatch {
  private Connection connection;
  private PreparedStatement ps;
  private PreparedStatement structInsert;
  String[] strings;

  @Param({"16", "128", "1024"})
  int p1nrows;

  @Param({"1", "4", "8", "16", "128"})
  int p2multi;

  @Setup(Level.Trial)
  public void setUp(BenchmarkParams bp) throws SQLException {
    // Test only
    //   1) p1nrows in (16, 128, 1024) && p2multi == 128
    //   2) p1nrows in (1024) && p2multi in (1, 2, 4, 4, 16)
    if (bp.getBenchmark().contains("insertExecute")) {
      if (p2multi != 1) {
        System.exit(-1);
      }
    } else if (!(p2multi == 128 || p1nrows == 1024)) {
      System.exit(-1);
    }
    p2multi = Math.min(p2multi, p1nrows);

    Properties props = ConnectionUtil.getProperties();

    if (bp.getBenchmark().contains("insertBatchWithRewrite")) {
      // PGProperty.REWRITE_BATCHED_INSERTS is not used for easier use with previous pgjdbc versions
      props.put("reWriteBatchedInserts", "true");
    }

    connection = DriverManager.getConnection(ConnectionUtil.getURL(), props);
    Statement s = connection.createStatement();
    ;
    try {
      s.execute("drop table batch_perf_test");
    } catch (SQLException e) {
            /* ignore */
    }
    s.execute("create table batch_perf_test(a int4, b varchar(100), c int4)");
    s.close();
    String sql = "insert into batch_perf_test(a, b, c) values(?, ?, ?)";
    for (int i = 1; i < p2multi; i++) {
      sql += ",(?,?,?)";
    }
    ps = connection.prepareStatement(sql);
    structInsert = connection.prepareStatement(
        "insert into batch_perf_test select * from unnest(?::batch_perf_test[])");

    strings = new String[p1nrows];
    for (int i = 0; i < p1nrows; i++) {
      strings[i] = "s" + i;
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() throws SQLException {
    ps.close();
    Statement s = connection.createStatement();
    s.execute("drop table batch_perf_test");
    s.close();
    connection.close();
  }

  @Benchmark
  public int[] insertBatch() throws SQLException {
    if (p2multi > 1) {
      // Multi values(),(),() case
      for (int i = 0; i < p1nrows; ) {
        for (int k = 0, pos = 1; k < p2multi; k++, i++) {
          ps.setInt(pos, i);
          pos++;
          ps.setString(pos, strings[i]);
          pos++;
          ps.setInt(pos, i);
          pos++;
        }
        ps.addBatch();
      }
    } else {
      // Regular insert() values(); case
      for (int i = 0; i < p1nrows; i++) {
        ps.setInt(1, i);
        ps.setString(2, strings[i]);
        ps.setInt(3, i);
        ps.addBatch();
      }
    }
    return ps.executeBatch();
  }

  @Benchmark
  public int[] insertBatchWithRewrite() throws SQLException {
    return insertBatch();
  }

  @Benchmark
  public void insertExecute(Blackhole b) throws SQLException {
    for (int i = 0; i < p1nrows; i++) {
      ps.setInt(1, i);
      ps.setString(2, strings[i]);
      ps.setInt(3, i);
      b.consume(ps.execute());
    }
  }

  @Benchmark
  public int[] insertStruct() throws SQLException, IOException {
    CharArrayWriter wr = new CharArrayWriter();

    for (int i = 0; i < p1nrows; ) {
      wr.reset();
      wr.append('{');
      for (int k = 0; k < p2multi; k++, i++) {
        if (k != 0) {
          wr.append(',');
        }
        wr.append("\"(");
        wr.append(Integer.toString(i));
        wr.append(',');
        String str = strings[i];
        if (str != null) {
          boolean hasQuotes = str.indexOf('"') != -1;
          if (hasQuotes) {
            wr.append("\"");
            wr.append(str.replace("\"", "\\\""));
            wr.append("\"");
          } else {
            wr.append(str);
          }
        }
        wr.append(',');
        wr.append(Integer.toString(i));
        wr.append(")\"");
      }
      wr.append('}');
      structInsert.setString(1, wr.toString());
      structInsert.addBatch();
    }

    return structInsert.executeBatch();
  }

  @Benchmark
  public void insertCopy(Blackhole b) throws SQLException, IOException {
    CopyManager copyAPI = ((PGConnection) connection).getCopyAPI();
    CharArrayWriter wr = new CharArrayWriter();
    for (int i = 0; i < p1nrows;) {
      CopyIn copyIn = copyAPI.copyIn("COPY batch_perf_test FROM STDIN");
      wr.reset();
      for (int k = 0; k < p2multi; k++, i++) {
        wr.append(Integer.toString(i));
        wr.append('\t');
        String str = strings[i];
        if (str != null) {
          boolean hasTabs = str.indexOf('\t') != -1;
          if (hasTabs) {
            wr.append("\"");
            wr.append(str.replace("\"", "\"\""));
            wr.append("\"");
          } else {
            wr.append(str);
          }
        }
        wr.append('\t');
        wr.append(Integer.toString(i));
        wr.append('\n');
      }
      byte[] bytes = wr.toString().getBytes("UTF-8");
      copyIn.writeToCopy(bytes, 0, bytes.length);
      b.consume(copyIn.endCopy());
    }
  }


  public static void main(String[] args) throws RunnerException {
    //DriverManager.setLogWriter(new PrintWriter(System.out));
    //Driver.setLogLevel(2);
    Options opt = new OptionsBuilder()
        .include(InsertBatch.class.getSimpleName())
        //.addProfiler(org.openjdk.jmh.profile.GCProfiler.class)
        //.addProfiler(org.postgresql.benchmark.profilers.FlightRecorderProfiler.class)
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
