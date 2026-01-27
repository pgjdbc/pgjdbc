/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.util;

import org.postgresql.util.PGInterval;

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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Tests the performance of {@link PGInterval#getValue()}.
 *
 * <p>
 * Here's a result for Apple M1 Max, Java 21.0.10, ARM64.
 * <pre>
 * Benchmark                       (inputType)  Mode  Cnt      Score      Error   Units
 * getValue                               ZERO  avgt   25      7,654 ±    0,249   ns/op
 * getValue:gc.alloc.rate.norm            ZERO  avgt   25    128,000 ±    0,001    B/op
 * getValue                     RANDOM_SECONDS  avgt   25     17,306 ±    0,681   ns/op
 * getValue:gc.alloc.rate.norm  RANDOM_SECONDS  avgt   25    136,000 ±    0,001    B/op
 * getValue                        RANDOM_DAYS  avgt   25     44,907 ±   18,940   ns/op
 * getValue:gc.alloc.rate.norm     RANDOM_DAYS  avgt   25    156,800 ±    4,893    B/op
 * getValue                       RANDOM_YEARS  avgt   25     74,583 ±    1,102   ns/op
 * getValue:gc.alloc.rate.norm    RANDOM_YEARS  avgt   25    182,401 ±    2,446    B/op
 * </pre>
 */
@Fork(value = 3, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class PGIntervalBenchmark {
  public enum InputType {
    ZERO, RANDOM_SECONDS, RANDOM_DAYS, RANDOM_YEARS
  }

  @Param
  InputType inputType;

  PGInterval pgInterval;

  @Setup
  public void setup() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    switch (inputType) {
      case ZERO:
        pgInterval = new PGInterval(0, 0, 0, 0, 0, 0);
        break;
      case RANDOM_SECONDS:
        pgInterval = new PGInterval(
            0, // years
            0, // months
            0, // days
            0, // hours
            0, // mins
            random.nextDouble(-60, 60) // seconds
        );
        break;
      case RANDOM_DAYS:
        pgInterval = new PGInterval(
            0, // years
            0, // months
            random.nextInt(-2, 2), // days
            random.nextInt(-23, 23), // hours
            random.nextInt(-59, 59), // mins
            random.nextDouble(-59, 59) // seconds
        );
        break;
      case RANDOM_YEARS:
        pgInterval = new PGInterval(
            random.nextInt(-2000, 2000), // years
            random.nextInt(-12, 12), // months
            random.nextInt(-31, 31), // days
            random.nextInt(-23, 23), // hours
            random.nextInt(-59, 59), // mins
            random.nextDouble(-59, 59) // seconds
        );
        break;
    }
  }

  @Benchmark
  public String getValue() {
    return pgInterval.getValue();
  }
}
