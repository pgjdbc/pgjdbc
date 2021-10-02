/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.bytea;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@Fork(value = 5, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 10, time = 10)
@Warmup(iterations = 1, time = 10)
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ByteaEncoding {

  private static char[] hexdigit = new char[]{
      '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'B', 'c', 'D', 'e', 'F',
  };
  private static final byte[] testInput1k = generateTestInput(1000);
  private static final byte[] testInput100k = generateTestInput(100000);

  @Benchmark
  public void runOriginalBenchmark() throws Exception {
    new OriginalTest(testInput1k).run();
    new OriginalTest(testInput100k).run();
  }

  @Benchmark
  public void runArray2DBenchmark() throws Exception {
    new Array2DTest(testInput1k).run();
    new Array2DTest(testInput100k).run();
  }

  interface TestRun {
    void run() throws Exception;
  }

  private static byte[] generateTestInput(int length) {
    Random rand = new Random(length); // predictable seed

    byte[] result = new byte[length];
    for (int i = 2; i < length; i += 2) {
      int randByte = rand.nextInt(256);
      result[i] = (byte) hexdigit[randByte >> 4];
      result[i + 1] = (byte) hexdigit[randByte & 15];
    }

    return result;
  }

  private static final class OriginalTest implements TestRun {
    private final byte[] input;

    OriginalTest(final byte[] input) {
      this.input = input;
    }

    public void run() throws Exception {
      toBytesHexEscaped(input);
    }

    private static void toBytesHexEscaped(final byte[] s) {
      final byte[] output = new byte[(s.length - 2) / 2];
      for (int i = 0; i < output.length; i++) {
        byte b1 = gethex(s[2 + i * 2]);
        byte b2 = gethex(s[2 + i * 2 + 1]);
        output[i] = (byte) ((b1 << 4) | b2);
      }
    }

    private static byte gethex(byte b) {
      // 0-9 == 48-57
      if (b <= 57) {
        return (byte) (b - 48);
      }

      // a-f == 97-102
      if (b >= 97) {
        return (byte) (b - 97 + 10);
      }

      // A-F == 65-70
      return (byte) (b - 65 + 10);
    }

    public String toString() {
      return "original code, " + input.length;
    }
  }

  private static final class Array2DTest implements TestRun {
    private final byte[] input;

    private static final byte[][] HEX_LOOKUP = new byte[103][103];

    static {
      // build the hex lookup table
      for (int i = 0; i < 256; ++i) {
        int lo = i & 0x0f;
        int lo2 = lo;
        int hi = (i & 0xf0) >> 4;
        int hi2 = hi;
        // 0-9 == 48-57
        // a-f == 97-102
        // A-F == 65-70
        if (lo < 10) {
          lo += 48;
          lo2 = lo;
        } else {
          lo += 87;
          lo2 += 55;
        }
        if (hi < 10) {
          hi += 48;
          hi2 = hi;
        } else {
          hi += 87;
          hi2 += 55;
        }
        HEX_LOOKUP[lo] [hi] = (byte)i;
        HEX_LOOKUP[lo2][hi] = (byte)i;
        HEX_LOOKUP[lo] [hi2] = (byte)i;
        HEX_LOOKUP[lo2][hi2] = (byte)i;
      }
    }

    Array2DTest(final byte[] input) {
      this.input = input;
    }

    public void run() throws Exception {
      toBytesHexEscaped(input);
    }

    private static void toBytesHexEscaped(final byte[] s) {
      final byte[] output = new byte[(s.length - 2) / 2];
      for (int i = 0; i < output.length; i++) {
        output[i] = HEX_LOOKUP[s[2 + i * 2 + 1]][s[2 + i * 2]];
      }
    }

    public String toString() {
      return "2D array lookup, " + input.length;
    }
  }

}
