package org.postgresql.benchmark.statement;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.postgresql.util.ConnectionUtil;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Here we measure the time it takes to create and close a dummy statement.
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
@Fork(value = 1, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FinalizeStatement
{
    @Param({"0", "1", "10", "100"})
    private int leakPct;

    private float leakPctFloat;

    private Connection connection;

    //#if mvn.project.property.current.jdk < "1.7"
    private Random random = new Random();
    //#endif

    @Setup(Level.Trial)
    public void setUp() throws SQLException
    {
        Properties props = ConnectionUtil.getProperties();

        connection = DriverManager.getConnection(ConnectionUtil.getURL(), props);
        leakPctFloat = 0.01f * leakPct;
    }

    @TearDown(Level.Trial)
    public void tearDown() throws SQLException
    {
        connection.close();
    }

    @Benchmark
    public Statement createAndLeak() throws SQLException
    {
        Statement statement = connection.createStatement();
        Random rnd;
        //#if mvn.project.property.current.jdk < "1.7"
        rnd = random;
        //#else
        rnd = java.util.concurrent.ThreadLocalRandom.current();
        //#endif
        if (rnd.nextFloat() >= leakPctFloat)
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
