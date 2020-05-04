/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.tfb;

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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * This is an implementation of TechEmpower/FrameworkBenchmarks / update benchmark. See details in
 * <a href="http://frameworkbenchmarks.readthedocs.io/en/latest/Project-Information/Framework-Tests/#database-updates">Framework
 * Tests Documentation</a>
 *
 * <p>Note, according to the benchmark rules, use of batch updates is acceptable but not required. To
 * be clear: batches are not permissible for selecting/reading the rows, but batches are acceptable
 * for writing the updates.</p>
 *
 * <p>The results as of 2017-07-28, PostgreSQL 9.6 are as follows. Notable findings: "update from
 * values" is faster than regular update batch.</p>
 * <pre>
 * # JMH 1.17.4 (released 191 days ago, please consider updating!)
 * # VM version: JDK 1.8.0_112, VM 25.112-b16
 * # VM invoker: /Library/Java/JavaVirtualMachines/jdk1.8.0_112.jdk/Contents/Home/jre/bin/java
 * # VM options: -Xmx128m
 * # Warmup: 6 iterations, 1 s each
 * # Measurement: 10 iterations, 1 s each
 * # Timeout: 10 min per iteration
 * # Threads: 4 threads, will synchronize iterations
 * # Benchmark mode: Average time, time/op
 *
 *   Benchmark                  (autoCommit)  (cnt)  Mode  Cnt    Score    Error  Units
 * Update.batch                      false     20  avgt    6    2,469 ±  0,194  ms/op
 * Update.batch_sort                  true     20  avgt   20    2,647 ±  0,063  ms/op
 * Update.batch_sort                  true     40  avgt   20    5,096 ±  0,115  ms/op
 * Update.batch_sort                  true    100  avgt   20   12,642 ±  0,651  ms/op
 * Update.batch_sort                  true   1000  avgt   20  120,412 ±  3,983  ms/op
 * Update.batch_sort                 false     20  avgt   20    2,743 ±  0,111  ms/op
 * Update.batch_sort                 false     40  avgt   20    5,001 ±  0,111  ms/op
 * Update.batch_sort                 false    100  avgt   20   11,944 ±  0,214  ms/op
 * Update.batch_sort                 false   1000  avgt   20  113,908 ±  2,693  ms/op
 * Update.get_worlds                  true     20  avgt   20    1,868 ±  0,014  ms/op
 * Update.get_worlds                  true     40  avgt   20    3,740 ±  0,069  ms/op
 * Update.get_worlds                  true    100  avgt   20    9,268 ±  0,095  ms/op
 * Update.get_worlds                  true   1000  avgt   20   93,280 ±  1,811  ms/op
 * Update.get_worlds                 false     20  avgt   20    1,870 ±  0,024  ms/op
 * Update.get_worlds                 false     40  avgt   20    3,792 ±  0,090  ms/op
 * Update.get_worlds                 false    100  avgt   20    9,365 ±  0,115  ms/op
 * Update.get_worlds                 false   1000  avgt   20   92,899 ±  1,040  ms/op
 * Update.single                      true     20  avgt   20    6,440 ±  0,328  ms/op
 * Update.single                      true     40  avgt   20   12,501 ±  0,333  ms/op
 * Update.single                      true    100  avgt   20   32,696 ±  3,382  ms/op
 * Update.single                      true   1000  avgt   20  307,325 ± 12,936  ms/op
 * Update.single                     false     20  avgt   20    4,218 ±  0,253  ms/op
 * Update.single                     false     40  avgt   20    7,999 ±  0,123  ms/op
 * Update.single                     false    100  avgt   20   19,721 ±  0,804  ms/op
 * Update.single                     false   1000  avgt   20  231,627 ± 10,899  ms/op
 * Update.update_from_select          true     20  avgt   20    2,483 ±  0,045  ms/op
 * Update.update_from_select          true     40  avgt   20    4,625 ±  0,065  ms/op
 * Update.update_from_select          true    100  avgt   20   14,223 ±  0,323  ms/op
 * Update.update_from_select          true   1000  avgt   20  332,941 ± 14,884  ms/op
 * Update.update_from_select         false     20  avgt   20    2,457 ±  0,044  ms/op
 * Update.update_from_select         false     40  avgt   20    4,522 ±  0,064  ms/op
 * Update.update_from_select         false    100  avgt   20   13,995 ±  0,338  ms/op
 * Update.update_from_select         false   1000  avgt   20  326,228 ± 15,964  ms/op
 * Update.update_from_values          true     20  avgt   20    2,234 ±  0,056  ms/op
 * Update.update_from_values          true     40  avgt   20    4,265 ±  0,083  ms/op
 * Update.update_from_values          true    100  avgt   20   13,351 ±  0,260  ms/op
 * Update.update_from_values          true   1000  avgt   20  100,317 ±  1,801  ms/op
 * Update.update_from_values         false     20  avgt   20    2,230 ±  0,024  ms/op
 * Update.update_from_values         false     40  avgt   20    4,181 ±  0,090  ms/op
 * Update.update_from_values         false    100  avgt   20   12,966 ±  0,195  ms/op
 * Update.update_from_values         false   1000  avgt   20   95,709 ±  2,041  ms/op
 * </pre>
 */
@Fork(value = 5, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Threads(4)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class Update {
  private static final int DB_ROWS = 10000;

  //@Param({"1", "5", "15", "20"})
  @Param({"20", "40", "100", "1000"})
  int cnt;

  @Param({"true", "false"})
  boolean autoCommit;

  private Connection con;

  private static class World {
    private static final Comparator<World> ASC = Comparator.comparing(World::getId);

    final int id;
    int randomNumber;

    private World(int id, int randomNumber) {
      this.id = id;
      this.randomNumber = randomNumber;
    }

    private int getId() {
      return id;
    }
  }

  @Setup(Level.Trial)
  public void setUp() throws SQLException {
    Properties props = ConnectionUtil.getProperties();
    con = DriverManager.getConnection(ConnectionUtil.getURL(), props);
  }

  @TearDown(Level.Trial)
  public void tearDown() throws SQLException {
    con.close();
  }

  private void begin() throws SQLException {
    con.setAutoCommit(autoCommit);
  }

  private void commit() throws SQLException {
    con.setAutoCommit(true);
  }

  @Benchmark
  public List<World> get_worlds() throws SQLException {
    return getWorlds(cnt);
  }

  @Benchmark
  public List<World> batch() throws SQLException {
    return batchUpdate(false);
  }

  @Benchmark
  public List<World> batch_sort() throws SQLException {
    return batchUpdate(true);
  }

  private List<World> batchUpdate(boolean sort) throws SQLException {
    begin();
    List<World> worlds = getWorlds(cnt);

    if (sort) {
      worlds.sort(World.ASC);
    }

    try (PreparedStatement ps = con.prepareStatement(
        "UPDATE World SET randomNumber = ? WHERE id = ?")) {
      for (World world: worlds) {
        ps.setInt(1, world.randomNumber);
        ps.setInt(2, world.id);
        ps.addBatch();
      }
      ps.executeBatch();
    }
    commit();
    return worlds;
  }

  @Benchmark
  public List<World> single() throws SQLException {
    begin();
    List<World> worlds = getWorlds(cnt);

    worlds.sort(World.ASC);

    try (PreparedStatement ps = con.prepareStatement(
        "UPDATE World SET randomNumber = ? WHERE id = ?")) {
      for (World world: worlds) {
        ps.setInt(1, world.randomNumber);
        ps.setInt(2, world.id);
        ps.execute();
      }
    }
    commit();
    return worlds;
  }

  @Benchmark
  public List<World> update_from_select() throws SQLException {
    begin();
    List<World> worlds = getWorlds(cnt);

    worlds.sort(World.ASC);

    StringBuilder sb = new StringBuilder("UPDATE World SET randomNumber = v.c1 FROM (");
    for (int i = 0; i < cnt; i++) {
      if (i > 0) {
        sb.append(" UNION ALL ");
      }
      sb.append("SELECT ? c1, ? c2");
    }
    sb.append(") v WHERE id = v.c2");

    updateWorlds(worlds, sb.toString());
    commit();
    return worlds;
  }

  @Benchmark
  public List<World> update_from_values() throws SQLException {
    begin();
    List<World> worlds = getWorlds(cnt);

    worlds.sort(World.ASC);

    StringBuilder sb = new StringBuilder("UPDATE World SET randomNumber = v.c1 FROM (VALUES");
    for (int i = 0; i < cnt; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("(?,?)");
    }
    sb.append(") as v(c1, c2) WHERE id = v.c2");

    updateWorlds(worlds, sb.toString());
    commit();
    return worlds;
  }

  private void updateWorlds(List<World> worlds, String sql) throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(sql)) {
      int i = 0;
      for (World world: worlds) {
        ps.setInt(i + 1, world.randomNumber);
        ps.setInt(i + 2, world.id);
        i += 2;
      }
      ps.execute();
    }
  }

  private List<World> getWorlds(int cnt) throws SQLException {
    Random rnd = ThreadLocalRandom.current();
    List<World> worlds = new ArrayList<>(cnt);
    try (PreparedStatement qry = con.prepareStatement("SELECT * FROM World WHERE id = ?")) {
      for (int i = 0; i < cnt; i++) {
        int id = rnd.nextInt(DB_ROWS) + 1;
        qry.setInt(1, id);
        try (ResultSet rs = qry.executeQuery()) {
          rs.next();
          worlds.add(new World(id, rs.getInt("randomNumber")));
        }
      }
    }
    return worlds;
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(Update.class.getSimpleName())
        // .addProfiler(GCProfiler.class)
        // .addProfiler(FlightRecorderProfiler.class)
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
