package org.postgresql.benchmark.statement;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.postgresql.util.ConnectionUtil;

import java.sql.*;
import java.util.Properties;
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
public class ParseStatement {
    @Param({"0", "1", "10", "20"})
    private int bindCount;

    private Connection connection;

    private String sql;

    @Setup(Level.Trial)
    public void setUp() throws SQLException {
        Properties props = ConnectionUtil.getProperties();

        connection = DriverManager.getConnection(ConnectionUtil.getURL(), props);
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ");
        for (int i = 0; i < bindCount; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        sql = sb.toString();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws SQLException {
        connection.close();
    }

    @Benchmark
    public Statement bindExecuteFetch(Blackhole b) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        for (int i = 1; i <= bindCount; i++) {
            ps.setInt(i, i);
        }
        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
            for (int i = 1; i <= bindCount; i++) {
                b.consume(rs.getInt(i));
            }
        }
        rs.close();
        ps.close();
        return ps;
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(ParseStatement.class.getSimpleName())
                .addProfiler(GCProfiler.class)
                .detectJvmArgs()
                .build();

        new Runner(opt).run();
    }
}
