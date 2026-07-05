/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jazzer;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.CodecFuzzSupport;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgType;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

/**
 * The binary-wire robustness invariant for the container decoders (blind spot Z1): feed arbitrary
 * bytes to the array, composite, and range binary decoders and assert each either returns a value or
 * refuses with a clean {@code SQLException}, never leaking an unchecked exception and never exhausting
 * the heap.
 *
 * <p>This is the binary sibling of {@link JazzerTextLiteralDecodeFuzzTest}, which drives the same
 * containers through their text-literal grammars. The two front-ends reach different code: the text
 * targets exercise the recursive {@code LiteralCursor}-based parsers, while these exercise the
 * length- and count-guarded binary framing -- the array header ({@code MultiDimArrayBinary}), the
 * composite field count ({@code CompositeCodec}), and the range bound lengths ({@code RangeCodec}).
 * The canonical-wire round-trip fuzzers leave every one of those error branches cold, because every
 * buffer they decode is one a matching encoder just produced; only raw bytes reach the truncated
 * header, the negative length, and the over-large element count a hostile or corrupt server could
 * send.
 *
 * <p>Jazzer mutates the wire bytes directly, so the property is the whole target -- no oracle and no
 * value generator. The invariant lives in the shared
 * {@link CodecFuzzSupport#decodeBinaryExpectingNoLeak} helper, so a leak surfaces the same way it
 * would under any other front-end.
 *
 * <p>The container OIDs come from the shared {@link PgTypeDescriptors} registry, except the range,
 * which the registry does not carry: it is built inline as an offline {@code int4range}
 * ({@code typtype='r'}, subtype {@code int4}) so {@code RangeCodec} resolves the bound codec without a
 * connection.
 *
 * <p>Phase F1 added the allocation bounds these decoders need -- the array {@code ndim}, the composite
 * field count, and the numeric digit count are all validated against the remaining buffer before
 * anything is allocated -- so a guided campaign runs this without an unbounded allocation bringing the
 * JVM down. That heap safety is why the container robustness targets live on Jazzer, which isolates an
 * {@code OutOfMemoryError} per input, rather than on JQF, where one would kill the test JVM.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jazzer-test:test}; fuzz one target with
 * {@code gradle :pgjdbc-jazzer-test:test -Pjazzer.fuzz=1 --tests '*int4ArrayBinary*'}.
 */
class JazzerBinaryContainerDecodeFuzzTest {

  private static final PgType INT4_ARRAY = PgTypeDescriptors.array(Oid.INT4_ARRAY).pgType();
  private static final PgType TEXT_ARRAY = PgTypeDescriptors.array(Oid.TEXT_ARRAY).pgType();
  private static final PgType POINT = PgTypeDescriptors.composite(PgTypeDescriptors.POINT_OID).pgType();

  /** The registered point composite so its field framing resolves offline. */
  private static final CodecContext POINT_CONTEXT =
      PgCodecContext.offlineBuilder().type(POINT).build();

  // An offline int4range: a range type (typtype='r') carrying its subtype OID directly, so RangeCodec
  // resolves the int4 bound codec without a connection (pg_range.rngsubtype is normally loaded lazily).
  // The OID is synthetic -- ranges have no pinned OID in the driver -- and stays clear of the built-in
  // range OIDs, so the type routes to RangeCodec by typtype rather than by a name alias.
  private static final int INT4RANGE_OID = 91_001;
  private static final PgType INT4RANGE =
      new PgType(new ObjectName("pg_catalog", "int4range"), "pg_catalog.int4range",
          INT4RANGE_OID, 'r', 'R', -1, 0, 0, 0).withRangeSubtype(Oid.INT4);
  private static final CodecContext INT4RANGE_CONTEXT =
      PgCodecContext.offlineBuilder().type(INT4RANGE).build();

  @FuzzTest
  void int4ArrayBinary(byte @NotNull [] data) {
    CodecFuzzSupport.decodeBinaryExpectingNoLeak(data, INT4_ARRAY, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void textArrayBinary(byte @NotNull [] data) {
    CodecFuzzSupport.decodeBinaryExpectingNoLeak(data, TEXT_ARRAY, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void compositeBinary(byte @NotNull [] data) {
    CodecFuzzSupport.decodeBinaryExpectingNoLeak(data, POINT, POINT_CONTEXT);
  }

  @FuzzTest
  void rangeBinary(byte @NotNull [] data) {
    CodecFuzzSupport.decodeBinaryExpectingNoLeak(data, INT4RANGE, INT4RANGE_CONTEXT);
  }
}
