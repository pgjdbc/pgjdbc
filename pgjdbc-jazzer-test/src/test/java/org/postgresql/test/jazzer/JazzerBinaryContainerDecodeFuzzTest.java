/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jazzer;

import org.postgresql.fuzzkit.CodecFuzzSupport;
import org.postgresql.fuzzkit.ContainerDecodeTypes;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

/**
 * The binary-wire robustness invariant for the container decoders (blind spot Z1): feed arbitrary
 * bytes to the array, composite, range, multirange, and domain binary decoders and assert each either
 * returns a value or refuses with a clean {@code SQLException}, never leaking an unchecked exception and
 * never exhausting the heap. The domain target also pins that {@code DomainCodec} forwards the value
 * offset to its base codec (a past offset-drop defect), which the offset-invariant helper checks.
 *
 * <p>This is the binary sibling of {@link JazzerTextLiteralDecodeFuzzTest}, which drives the same
 * containers through their text-literal grammars. The two front-ends reach different code: the text
 * targets exercise the recursive {@code LiteralCursor}-based parsers, while these exercise the
 * length- and count-guarded binary framing -- the array header ({@code MultiDimArrayBinary}), the
 * composite field count ({@code CompositeCodec}), the range bound lengths ({@code RangeCodec}), and the
 * multirange element count and per-range lengths ({@code MultirangeCodec}). The canonical-wire round-trip
 * fuzzers leave every one of those error branches cold, because every buffer they decode is one a matching
 * encoder just produced; only raw bytes reach the truncated header, the negative length, and the over-large
 * element count a hostile or corrupt server could send.
 *
 * <p>Jazzer mutates the wire bytes directly, so the property is the whole target -- no oracle and no
 * value generator. The invariant lives in the shared
 * {@link CodecFuzzSupport#decodeBinaryOffsetInvariant} helper, which also decodes each buffer from a
 * non-zero offset and asserts the two agree, so a container decoder that mishandles the value offset is
 * caught the same way it would be under any other front-end.
 *
 * <p>The container types come from {@link ContainerDecodeTypes}: the arrays and the composite from the
 * shared descriptor registry, the range and multirange built inline (the registry carries neither) so they
 * resolve their bound codec offline.
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

  @FuzzTest
  void int4ArrayBinary(byte @NotNull [] data) {
    CodecFuzzSupport.decodeBinaryOffsetInvariant(data, ContainerDecodeTypes.INT4_ARRAY,
        CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void textArrayBinary(byte @NotNull [] data) {
    CodecFuzzSupport.decodeBinaryOffsetInvariant(data, ContainerDecodeTypes.TEXT_ARRAY,
        CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void compositeBinary(byte @NotNull [] data) {
    CodecFuzzSupport.decodeBinaryOffsetInvariant(data, ContainerDecodeTypes.POINT,
        ContainerDecodeTypes.POINT_CONTEXT);
  }

  @FuzzTest
  void rangeBinary(byte @NotNull [] data) {
    CodecFuzzSupport.decodeBinaryOffsetInvariant(data, ContainerDecodeTypes.INT4RANGE,
        ContainerDecodeTypes.INT4RANGE_CONTEXT);
  }

  @FuzzTest
  void multirangeBinary(byte @NotNull [] data) {
    CodecFuzzSupport.decodeBinaryOffsetInvariant(data, ContainerDecodeTypes.INT4MULTIRANGE,
        ContainerDecodeTypes.INT4MULTIRANGE_CONTEXT);
  }

  @FuzzTest
  void domainBinary(byte @NotNull [] data) {
    CodecFuzzSupport.decodeBinaryOffsetInvariant(data, ContainerDecodeTypes.INT4_DOMAIN,
        ContainerDecodeTypes.INT4_DOMAIN_CONTEXT);
  }
}
