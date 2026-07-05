/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jazzer;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.RawValue;
import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.CodecFuzzSupport;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.jdbc.PgType;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * A decode-untrusted-input invariant, the shape coverage-guided fuzzing is strongest at and the one the
 * round-trip properties in pgjdbc-jqf-test do not reach: feed arbitrary bytes to a scalar decoder as a
 * wire value and assert it either decodes or refuses with a {@link SQLException} -- never an unchecked
 * leak ({@code NumberFormatException}, {@code ArrayIndexOutOfBounds}, {@code NullPointerException}, ...).
 *
 * <p>Jazzer mutates the raw wire bytes directly, so no oracle and no value generator are needed; the
 * property is the whole target. This is the qualitative difference from the round-trip suite, which only
 * ever hands the decoder bytes a matching encoder produced -- it cannot exercise the malformed-wire path a
 * hostile or corrupt server could send. The scalar {@link PgType}s come from the shared
 * {@link PgTypeDescriptors} registry.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jazzer-test:test}; fuzz with
 * {@code gradle :pgjdbc-jazzer-test:test -Pjazzer.fuzz=1 --tests '*numericTextDecode*'}.
 */
class JazzerDecodeRobustnessFuzzTest {

  private static final PgType NUMERIC = PgTypeDescriptors.scalar(Oid.NUMERIC).pgType();
  private static final PgType INT4 = PgTypeDescriptors.scalar(Oid.INT4).pgType();
  private static final PgType TEXT = PgTypeDescriptors.scalar(Oid.TEXT).pgType();

  @FuzzTest
  void numericTextDecodeNeverLeaksUnchecked(byte @NotNull [] data) {
    decodeQuietly(NUMERIC, RawValue.text(data), BigDecimal.class);
  }

  // This target originally failed on the very first (empty) input: a 0-byte binary numeric made
  // ByteConverter.numeric throw IllegalArgumentException ("number of bytes should be at-least 8")
  // through NumericCodec.decodeBinaryAs, an unchecked leak rather than a SQLException. Roadmap phase
  // F1 fixed the driver (NumericCodec now wraps ByteConverter.numeric and refuses malformed binary
  // wire with a PSQLException), so the target is green in bounded regression and can join the fuzz.
  @FuzzTest
  void numericBinaryDecodeNeverLeaksUnchecked(byte @NotNull [] data) {
    decodeQuietly(NUMERIC, RawValue.binary(data), BigDecimal.class);
  }

  @FuzzTest
  void int4BinaryDecodeNeverLeaksUnchecked(byte @NotNull [] data) {
    decodeQuietly(INT4, RawValue.binary(data), Integer.class);
  }

  @FuzzTest
  void textDecodeNeverLeaksUnchecked(byte @NotNull [] data) {
    for (Format format : Format.values()) {
      RawValue raw = format == Format.TEXT ? RawValue.text(data) : RawValue.binary(data);
      decodeQuietly(TEXT, raw, String.class);
    }
  }

  /**
   * Decodes {@code raw} and swallows a {@link SQLException} -- the only failure the contract permits.
   * Any other throwable (an unchecked exception) escapes, which is exactly what the fuzzer should flag.
   */
  private static void decodeQuietly(PgType type, RawValue raw, Class<?> target) {
    CodecContext ctx = CodecFuzzSupport.builtins();
    try {
      Codecs.decode(raw, type, ctx, target);
    } catch (SQLException permitted) {
      // A decoder is allowed to reject malformed bytes with a SQLException; that is not a finding.
    }
  }
}
