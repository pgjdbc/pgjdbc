/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.encoding;

import org.postgresql.core.Encoding;

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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.concurrent.TimeUnit;

/**
 * Tests the performance of UTF-8 decoding. UTF-8 is used a lot, so we need to know the performance
 */
@Fork(value = 1, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class UTF8Decoding {

  private static final Charset UTF_8 = Charset.forName("UTF-8");

  @Param({"1", "5", "10", "50", "100"})
  public int length;

  private byte[] source;
  private CharsetDecoder decoder;
  private Encoding encoding;
  private Encoding encodingVol;
  private Encoding atomicEncoding;
  private CharBuffer buf;

  @Setup
  public void setup() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < length; i++) {
      sb.append("Hello мир,");
    }
    source = sb.toString().getBytes(UTF_8);
    decoder = UTF_8.newDecoder();
    decoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
    encoding = Encoding.getJVMEncoding("UTF-8");
    encodingVol = new VolatileUTF8Encoding();
    atomicEncoding = new AtomicUTF8Encoding();
    buf = CharBuffer.allocate(source.length);
  }

  @Benchmark
  public char[] utilsDecodeUTF8_old() {
    CharBuffer buf = UTF_8.decode(ByteBuffer.wrap(source));
    char[] c = new char[buf.limit()];
    buf.get(c, 0, buf.limit());
    return c;
  }

  @Benchmark
  public String encodingDecodeUTF8_current() throws IOException {
    return encoding.decode(source, 0, source.length);
  }

  @Benchmark
  public String encodingDecodeUTF8_volatile() throws IOException {
    return encodingVol.decode(source, 0, source.length);
  }

  @Benchmark
  public String encodingDecodeUTF8_atomic() throws IOException {
    return atomicEncoding.decode(source, 0, source.length);
  }

  @Benchmark
  public String string_string() throws UnsupportedEncodingException {
    return new String(source, 0, source.length, "UTF-8");
  }

  @Benchmark
  public String string_charset() {
    return new String(source, 0, source.length, UTF_8);
  }

  @Benchmark
  public String decoder_byteBufferReuse() throws CharacterCodingException {
    buf.clear();
    decoder.decode(ByteBuffer.wrap(source), buf, true);
    return new String(buf.array(), 0, buf.position());
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(UTF8Decoding.class.getSimpleName())
        //.addProfiler(GCProfiler.class)
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
