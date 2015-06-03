package org.postgresql.benchmark.core;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * Tests the performance of {@link org.postgresql.core.Parser#unmarkDoubleQuestion}.
 * This method is invoked at each query parse, so it might affect response times.
 */
@Fork(value = 1, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class Parser {
    @Param({"1", "10", "100"})
    public int sqlSize;

    @Param({"false", "true"})
    public boolean withDoubleQuestionmark;

    @Param("20")
    public int literalLength;

    private String sql;

    @Setup
    public void setup() {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT 1 where 1 ~?~> 2");
        for (int i = 0; i < sqlSize; i++) {
            // Double quotes
            sb.append(" \"");
            for (int j = 0; j < literalLength; j++)
                sb.append('a');
            sb.append('"');

            sb.append(" '");
            for (int j = 0; j < literalLength/2; j++)
                sb.append('b');
            sb.append("''");
            for (int j = 0; j < literalLength/2; j++)
                sb.append('b');
            sb.append('\'');

            sb.append(" /*");
            for (int j = 0; j < literalLength; j++)
                sb.append('c');
            sb.append("*/");
        }

        if (withDoubleQuestionmark) sb.append("??");
        sql = sb.toString();
    }


    @Benchmark
    public String unmarkDoubleQuestion() {
        return org.postgresql.core.Parser.unmarkDoubleQuestion(sql, true);
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
                .include(Parser.class.getSimpleName())
                .addProfiler(GCProfiler.class)
                .detectJvmArgs()
                .build();

        new Runner(opt).run();
    }
}
