/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.benchmark.time;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.sql.Timestamp;
import java.util.concurrent.TimeUnit;

@Fork(value = 0, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class AddPaddingZeros {

    @Param({"1000", "1000000", "100000000"})
    int nanos;

    static final char[] ZEROS = "0000000000".toCharArray();

    Timestamp ts = new Timestamp(System.currentTimeMillis());

    StringBuffer sb = new StringBuffer();

    @Setup
    public void init() {
        ts.setNanos(nanos);
    }

    @Benchmark
    public void charArray() {
        sb.setLength(0);
        int nanos = ts.getNanos();
        char[] decimalStr = {'0', '0', '0', '0', '0', '0', '0', '0', '0'};
        char[] nanoStr = Integer.toString(nanos).toCharArray();
        System.arraycopy(nanoStr, 0, decimalStr, decimalStr.length - nanoStr.length, nanoStr.length);
        sb.append(decimalStr, 0, 6);
    }

    @Benchmark
    public void insert() {
        sb.setLength(0);
        int len = sb.length();
        int nanos = ts.getNanos();
        sb.append(nanos / 1000);
        int needZeros = 6 - (sb.length() - len);
        if (needZeros > 0) {
            sb.insert(len, ZEROS, 0, needZeros);
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(AddPaddingZeros.class.getSimpleName())
                .addProfiler(GCProfiler.class)
//                .addProfiler(FlightRecorderProfiler.class)
                .detectJvmArgs()
                .build();

        new Runner(opt).run();
    }
}
