package org.postgresql.benchmark.statement;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.postgresql.benchmark.profilers.FlightRecorderProfiler;
import org.postgresql.util.ConnectionUtil;

import java.sql.*;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Tests the performance of preparing, executing and performing a fetch out of a simple "SELECT ?, ?, ... ?" statement.
 */
@Fork(value = 1, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ProcessResultSet {
    public enum FieldType {
        INT,
        BIGINT,
        BIGDECIMAL,
        STRING,
        TIMESTAMP,
        TIMESTAMPTZ,
    }

    public enum GetterType {
        BEST,
        OBJECT
    }

    @Param({"1", "50", "100"})
    private int nrows;

    @Param({"5"})
    private int ncols;

    @Param
    private FieldType type;

    @Param({"false"})
    public boolean unique;

    @Param({"BEST"})
    public GetterType getter;

    private Connection connection;

    private String sql;

    private int cntr;

    @Setup(Level.Trial)
    public void setUp() throws SQLException {
        if (type == FieldType.TIMESTAMP) {
            System.out.println("TimeZone.getDefault().getDisplayName() = " + TimeZone.getDefault().getDisplayName());
        }

        Properties props = ConnectionUtil.getProperties();
        connection = DriverManager.getConnection(ConnectionUtil.getURL(), props);
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ");
        for (int i = 0; i < ncols; i++) {
            if (i > 0) sb.append(", ");
            if (type == FieldType.INT) {
                sb.append("t.x");
            } if (type == FieldType.BIGINT) {
                sb.append("1234567890123456789");
            } if (type == FieldType.BIGDECIMAL) {
                sb.append("12345678901234567890123456789");
            } else if (type == FieldType.STRING) {
                sb.append("'test string'");
            } else if (type == FieldType.TIMESTAMP) {
                sb.append("localtimestamp");
            } else if (type == FieldType.TIMESTAMPTZ) {
                sb.append("current_timestamp");
            }
            sb.append(" c").append(i);
        }
        sb.append(" from generate_series(1, ?) as t(x)");
        sql = sb.toString();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws SQLException {
        connection.close();
    }

    @Benchmark
    public Statement bindExecuteFetch(Blackhole b) throws SQLException {
        String sql = this.sql;
        if (unique) sql += " -- " + cntr++;
        PreparedStatement ps = connection.prepareStatement(sql);
        ps.setInt(1, nrows);
        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
            for (int i = 1; i <= ncols; i++) {
                if (getter == GetterType.OBJECT) {
                    b.consume(rs.getObject(i));
                } else if (type == FieldType.INT) {
                    b.consume(rs.getInt(i));
                } else if (type == FieldType.BIGINT) {
                    b.consume(rs.getBigDecimal(i));
                } else if (type == FieldType.BIGDECIMAL) {
                    b.consume(rs.getBigDecimal(i));
                } else if (type == FieldType.STRING) {
                    b.consume(rs.getString(i));
                } else if (type == FieldType.TIMESTAMP) {
                    b.consume(rs.getTimestamp(i));
                } else if (type == FieldType.TIMESTAMPTZ) {
                    b.consume(rs.getTimestamp(i));
                }
            }
        }
        rs.close();
        ps.close();
        return ps;
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(ProcessResultSet.class.getSimpleName())
//                .addProfiler(GCProfiler.class)
                .detectJvmArgs()
                .build();

        new Runner(opt).run();
    }
}
