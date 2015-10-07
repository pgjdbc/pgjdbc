/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.benchmark.statement;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.postgresql.benchmark.profilers.FlightRecorderProfiler;
import org.postgresql.util.ConnectionUtil;

import java.sql.*;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Fork(value = 0, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class BindBoolean {
    private Connection connection;
    private PreparedStatement ps;

    @Setup(Level.Trial)
    public void setUp() throws SQLException {
        Properties props = ConnectionUtil.getProperties();

        connection = DriverManager.getConnection(ConnectionUtil.getURL(), props);
        ps = connection.prepareStatement("select ?");
    }

    @TearDown(Level.Trial)
    public void tearDown() throws SQLException {
        ps.close();
        connection.close();
    }

    @Benchmark
    public Statement boolAsInt() throws SQLException {
        ps.setObject(1, 1, Types.BOOLEAN);
        return ps;
    }

    @Benchmark
    public Statement boolAsBoolean() throws SQLException {
        ps.setObject(1, true, Types.BOOLEAN);
        return ps;
    }

    @Benchmark
    public Statement bindBoolean() throws SQLException {
        ps.setBoolean(1, true);
        return ps;
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(BindBoolean.class.getSimpleName())
                .addProfiler(GCProfiler.class)
                .addProfiler(FlightRecorderProfiler.class)
                .detectJvmArgs()
                .build();

        new Runner(opt).run();
    }
}
