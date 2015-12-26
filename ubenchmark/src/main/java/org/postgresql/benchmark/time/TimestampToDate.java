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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.sql.Timestamp;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

@Fork(value = 1, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TimestampToDate {
    private static final long ONEDAY = TimeUnit.DAYS.toMillis(1);

    @Param({"GMT+02:00", "Europe/Moscow"})
    String tz;
    TimeZone timeZone;

    Timestamp ts = new Timestamp(System.currentTimeMillis());
    Calendar cachedCalendar = new GregorianCalendar();

    @Setup
    public void init() {
        timeZone = TimeZone.getTimeZone(tz);
    }

    @Benchmark
    public long simple() {
        long millis = ts.getTime() + 10;
        ts.setTime(millis);
        Calendar cal = cachedCalendar;
        cal.setTimeZone(timeZone);
        cal.setTimeInMillis(millis);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private static boolean isSimpleTimeZone(String id)
    {
        return id.startsWith("GMT") || id.startsWith("UTC");
    }

    @Benchmark
    public long advanced() {
        long millis = ts.getTime() + 10;
        TimeZone tz = this.timeZone;
        if (isSimpleTimeZone(tz.getID()))
        {
            // Truncate to 00:00 of the day.
            // Suppose the input date is 7 Jan 15:40 GMT+02:00 (that is 13:40 UTC)
            // We want it to become 7 Jan 00:00 GMT+02:00
            // 1) Make sure millis becomes 15:40 in UTC, so add offset
            int offset = tz.getRawOffset();
            millis += offset;
            // 2) Truncate hours, minutes, etc. Day is always 86400 seconds, no matter what leap seconds are
            millis = millis / ONEDAY * ONEDAY;
            // 2) Now millis is 7 Jan 00:00 UTC, however we need that in GMT+02:00, so subtract some offset
            millis -= offset;
            // Now we have brand-new 7 Jan 00:00 GMT+02:00
            return millis;
        }
        Calendar cal = cachedCalendar;
        cal.setTimeZone(tz);
        cal.setTimeInMillis(millis);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(TimestampToDate.class.getSimpleName())
//                .addProfiler(GCProfiler.class)
//                .addProfiler(FlightRecorderProfiler.class)
                .detectJvmArgs()
                .build();

        new Runner(opt).run();
    }

}
