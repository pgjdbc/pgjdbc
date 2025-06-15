package org.postgresql.benchmark.statement;

import org.postgresql.test.TestUtil;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.postgresql.benchmark.profilers.FlightRecorderProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.sql.*;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@Fork(value = 5, jvmArgsPrepend = "-Xmx128m")
@Warmup(iterations = 5,  time = 500,   timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 1,     timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)

public class SelectBatch {

  public enum Scenario { CACHED_REPEAT, VARYING_LENGTH }

  @Param({ "CACHED_REPEAT", "VARYING_LENGTH" })
  private Scenario scenario;

  @Param({"1", "100", "1000"})
  private int batchSize;

  private Connection connection;
  private final String tableName = "bench_select";
  private String singleSql;
  private String cachedSql;
  private String[] varyingSqls;

  @Setup(Level.Trial)
  public void setUp() throws SQLException {
    connection = TestUtil.openDB();
    try (Statement st = connection.createStatement()) {
      st.execute("DROP TABLE IF EXISTS " + tableName);
      st.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, val TEXT)");
    }
    try (PreparedStatement ins = connection.prepareStatement(
        "INSERT INTO " + tableName + "(id,val) VALUES (?,?)")) {
      for (int i = 1; i <= batchSize; i++) {
        ins.setInt(1, i);
        ins.setString(2, "строка_" + i);
        ins.addBatch();
      }
      ins.executeBatch();
    }
    singleSql = "SELECT val FROM " + tableName + " WHERE id = ?";
    {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < batchSize; i++) {
        if (i > 0) sb.append(';');
        sb.append(singleSql);
      }
      cachedSql = sb.toString();
    }
    varyingSqls = new String[batchSize];
    for (int len = 1; len <= batchSize; len++) {
      StringBuilder sb = new StringBuilder();
      for (int j = 0; j < len; j++) {
        if (j > 0) sb.append(';');
        sb.append(singleSql);
      }
      varyingSqls[len - 1] = sb.toString();
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() throws SQLException {
    connection.close();
  }

  @Benchmark
  public void benchSelect(Blackhole bh) throws SQLException {
    switch (scenario) {

    case CACHED_REPEAT:
      try (PreparedStatement ps = connection.prepareStatement(cachedSql)) {
        for (int idx = 1; idx <= batchSize; idx++) {
          ps.setInt(idx, idx);
        }
        boolean hasResult = ps.execute();
        bh.consume(hasResult);
      }
      break;

    case VARYING_LENGTH:
      for (int len = 1; len <= batchSize; len++) {
        String sql = varyingSqls[len - 1];
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
          for (int idx = 1; idx <= len; idx++) {
            ps.setInt(idx, idx);
          }
          boolean hasResult = ps.execute();
          bh.consume(hasResult);
        }
      }
      break;
    }
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(SelectBatch.class.getSimpleName())
        .addProfiler(GCProfiler.class)
        .addProfiler(FlightRecorderProfiler.class)
        .detectJvmArgs()
        .build();
    new Runner(opt).run();
  }
}

