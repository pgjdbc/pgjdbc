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
import java.util.Calendar;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

@Fork(value = 1, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class BindTimestamp {
    private Connection connection;
    private PreparedStatement ps;
    private Timestamp ts = new Timestamp(System.currentTimeMillis());
    private Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

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
    public Statement timestampLocal() throws SQLException {
        ps.setTimestamp(1, ts);
        return ps;
    }

    @Benchmark
    public Statement timestampCal() throws SQLException {
        ps.setTimestamp(1, ts, cal);
        return ps;
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(BindTimestamp.class.getSimpleName())
                .addProfiler(GCProfiler.class)
                .addProfiler(FlightRecorderProfiler.class)
                .detectJvmArgs()
                .build();

        new Runner(opt).run();
    }
}
