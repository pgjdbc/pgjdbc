package org.postgresql.benchmark.statement;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.postgresql.PGProperty;
import org.postgresql.util.ConnectionUtil;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Compares {@link java.sql.Statement} with finalizer and without it.
 *
 * <p>This package contains micro-benchmarks to test postgresql jdbc driver performance.
 *
 * <p>To run this and other benchmarks:
 *
 * <blockquote>
 *   <code>mvn package &amp;&amp;
 *   java -classpath postgresql-driver.jar;target\benchmarks.jar -Duser=postgres -Dpassword=postgres -Dport=5433  -wi 10 -i 10 -f 1</code>
 * </blockquote>
 *
 * <p>To run with profiling:
 *
 * <blockquote>
 *   <code>java -classpath postgresql-driver.jar;target\benchmarks.jar
 *     -prof gc -f 1 -wi 10 -i 10</code>
 * </blockquote>
 */
@Fork(1)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FinalizeStatement
{
    @Param({"true", "false"})
    private boolean autoCloseStatements;

    private Connection connection;

    @Setup(Level.Trial)
    public void setUp() throws SQLException
    {
        Properties props = ConnectionUtil.getProperties();
        //not uses PGProperty.AUTO_CLOSE_UNCLOSED_STATEMENTS for run benchmark with old driver version
        props.setProperty("autoCloseUnclosedStatements", String.valueOf(autoCloseStatements));

        connection = DriverManager.getConnection(ConnectionUtil.getURL(), props);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws SQLException
    {
        connection.close();
    }

    @Benchmark
    public Statement createStatement() throws SQLException
    {
        Statement statement = connection.createStatement();
        statement.close();
        return statement;
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(FinalizeStatement.class.getSimpleName())
            .addProfiler(GCProfiler.class)
            .detectJvmArgs()
            .build();

        new Runner(opt).run();
    }
}
