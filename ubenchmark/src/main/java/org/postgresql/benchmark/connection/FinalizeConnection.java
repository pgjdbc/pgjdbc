package org.postgresql.benchmark.connection;

import org.openjdk.jmh.annotations.*;
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
 * Tests the time and memory required to create a connection.
 * Note: due to TCP socket's turning into TIME_WAIT state on close, it is
 * rather hard to test lots of connection creations, so only 50 iterations are performed.
 *
 * <p>To run this and other benchmarks (you can run the class from within IDE):
 *
 * <blockquote>
 *   <code>mvn package &amp;&amp;
 *   java -classpath postgresql-driver.jar:target/benchmarks.jar -Duser=postgres -Dpassword=postgres -Dport=5433  -wi 10 -i 10 -f 1</code>
 * </blockquote>
 *
 * <p>To run with profiling:
 *
 * <blockquote>
 *   <code>java -classpath postgresql-driver.jar:target/benchmarks.jar
 *     -prof gc -f 1 -wi 10 -i 10</code>
 * </blockquote>
 */
@Fork(1)
@Measurement(iterations = 50)
@Warmup(iterations = 10)
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class FinalizeConnection
{
    private Properties connectionProperties;
    private String connectionUrl;
    private Driver driver;

    @Setup(Level.Trial)
    public void setUp() throws SQLException
    {
        Properties props = ConnectionUtil.getProperties();

        connectionProperties = props;
        connectionUrl = ConnectionUtil.getURL();
        driver = DriverManager.getDriver(connectionUrl);
    }

    @Benchmark
    public void baseline() throws SQLException
    {
    }

    @Benchmark
    public Connection createAndClose() throws SQLException
    {
        Connection connection = driver.connect(connectionUrl, connectionProperties);
        connection.close();
        return connection;
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(FinalizeConnection.class.getSimpleName())
            .addProfiler(GCProfiler.class)
            .detectJvmArgs()
            .build();

        new Runner(opt).run();
    }
}
