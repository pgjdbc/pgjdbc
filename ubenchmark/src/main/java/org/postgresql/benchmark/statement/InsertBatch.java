/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.benchmark.statement;

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
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

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
    String[] strings;

    @Param({"100"})
    int nrows;

    @Setup(Level.Trial)
    public void setUp() throws SQLException {
        Properties props = ConnectionUtil.getProperties();

        connection = DriverManager.getConnection(ConnectionUtil.getURL(), props);
        Statement s = connection.createStatement();;
        try {
            s.execute("drop table batch_perf_test");
        } catch(SQLException e) {
            /* ignore */
        }
        s.execute("create table batch_perf_test(a int4, b varchar(100), c int4)");
        s.close();
        ps = connection.prepareStatement("insert into batch_perf_test(a, b, c) values(?, ?, ?)");
        strings = new String[nrows];
        for (int i = 0; i < nrows; i++) {
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
        for (int i = 0; i < nrows; i++) {
            ps.setInt(1, i);
            ps.setString(2, strings[i]);
            ps.setInt(3, i);
            ps.addBatch();
        }
        return ps.executeBatch();
    }

    @Benchmark
    public void insertExecute(Blackhole b) throws SQLException {
        for (int i = 0; i < nrows; i++) {
            ps.setInt(1, i);
            ps.setString(2, strings[i]);
            ps.setInt(3, i);
            b.consume(ps.execute());
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(InsertBatch.class.getSimpleName())
                .addProfiler(GCProfiler.class)
//                .addProfiler(FlightRecorderProfiler.class)
                .detectJvmArgs()
                .build();

        new Runner(opt).run();
    }
}
