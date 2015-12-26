/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.benchmark.encoding;

import org.postgresql.core.Utils;

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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.concurrent.TimeUnit;

/**
 * Tests the performance of UTF-8 encoding.
 * UTF-8 is used a lot, so we need to know the performance
 */
@Fork(value = 1, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class UTF8Encoding {

    @Param({"1", "5", "10", "50", "100"})
    public int length;

    private String source;
    private CharsetEncoder encoder;
    private ByteBuffer buf;
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    @Setup
    public void setup() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append("Hello мир,");
        }
        source = sb.toString();
        encoder = UTF_8.newEncoder();
        buf = ByteBuffer.allocate(10240);
    }

    @Benchmark
    public byte[] utilsEncodeUTF8_old() {
        ByteBuffer buf = UTF_8.encode(CharBuffer.wrap(source));
        byte[] b = new byte[buf.limit()];
        buf.get(b, 0, buf.limit());
        return b;
    }

    @Benchmark
    public byte[] utilsEncodeUTF8_current() {
        return Utils.encodeUTF8(source);
    }

    @Benchmark
    public byte[] string_getBytes() {
        return source.getBytes(UTF_8);
    }

    @Benchmark
    public ByteBuffer charset_encode() {
        return UTF_8.encode(source);
    }

    @Benchmark
    public Object encoder_byteBufferReuse() throws CharacterCodingException {
        buf.clear();
        return encoder.encode(CharBuffer.wrap(source), buf, true);
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(UTF8Encoding.class.getSimpleName())
//                .addProfiler(GCProfiler.class)
                .detectJvmArgs()
                .build();

        new Runner(opt).run();
    }
}
