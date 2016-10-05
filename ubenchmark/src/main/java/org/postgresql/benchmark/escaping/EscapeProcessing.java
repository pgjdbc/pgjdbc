package org.postgresql.benchmark.escaping;

import org.openjdk.jmh.annotations.*;
import org.postgresql.core.Parser;

import java.util.concurrent.TimeUnit;

@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class EscapeProcessing {

    @Benchmark
    public void escapeFunctionWithDate() throws Exception {
        Parser.replaceProcessing("{fn week({d '2005-01-24'})}", true, false);
    }
}
