/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.CodecFormatSupport;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.RawValue;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.CodecRegistry;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgType;
import org.postgresql.test.data.EdgeCase;
import org.postgresql.test.data.Int4EdgeCases;
import org.postgresql.test.data.Int8EdgeCases;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Checks the real registered codecs against the format-capability contract the fake matrix in
 * {@link CodecFormatSupportDifferentialTest} cannot reach. The fakes prove the dispatch logic is
 * correct for every capability combination; these tests prove the built-in codecs are honest about
 * theirs — a codec that declares it cannot read a format is actually refused by
 * {@link Codecs#decode}, and one that can round-trips real edge-case values through
 * {@link Codecs#encode}/{@link Codecs#decode}.
 */
class RegisteredCodecFormatCapabilityTest {

  @Test
  void everyBuiltinRefusesFormatsItCannotRead() {
    Map<Integer, Codec> builtins = new CodecRegistry().builtinCodecsByOid();
    int binaryRefusals = 0;
    for (Map.Entry<Integer, Codec> entry : builtins.entrySet()) {
      Codec codec = entry.getValue();
      CodecContext ctx = new SingleCodecContext(codec);
      TypeDescriptor type = builtinType(codec.getTypeName(), entry.getKey());
      String name = codec.getTypeName();

      if (!CodecFormatSupport.canReadBinary(codec)) {
        RawValue binary = RawValue.binary(new byte[0]);
        assertThrows(SQLException.class, () -> Codecs.decode(binary, type, ctx, Object.class),
            name + " declares it cannot read binary, so Codecs.decode(BINARY) must refuse it");
        binaryRefusals++;
      }
      if (!CodecFormatSupport.canReadText(codec)) {
        RawValue text = RawValue.text(new byte[0]);
        assertThrows(SQLException.class, () -> Codecs.decode(text, type, ctx, Object.class),
            name + " declares it cannot read text, so Codecs.decode(TEXT) must refuse it");
      }
    }
    // money keeps a text-only receive format, so the binary-refusal branch is never vacuous; a zero
    // here means the loop stopped exercising the refusal path at all.
    assertTrue(binaryRefusals > 0,
        "expected at least one built-in (money) to refuse a binary receive");
  }

  @Test
  void numericBuiltinsRoundTripEdgeCasesThroughEnforcePath() throws SQLException {
    CodecContext ctx = OfflineCodecs.builder().build();
    assertEdgeCasesRoundTrip(ctx, Oid.INT4, Int4EdgeCases.ALL);
    assertEdgeCasesRoundTrip(ctx, Oid.INT8, Int8EdgeCases.ALL);
  }

  private static void assertEdgeCasesRoundTrip(CodecContext ctx, int oid, List<EdgeCase> cases)
      throws SQLException {
    TypeDescriptor type = ctx.resolveType(oid);
    Codec codec = ctx.resolveCodec(oid);
    String name = type.getTypeName().toString();
    int checked = 0;
    for (EdgeCase edge : cases) {
      Object value = edge.value();
      if (value == null) {
        continue;
      }
      // A numeric codec writes both formats, and encode must honour the requested one, never fall
      // back, so a successful binary encode is a real binary payload.
      assertTrue(CodecFormatSupport.canWriteBinary(codec, value, type, ctx),
          name + " " + edge + " canWriteBinary");
      assertTrue(CodecFormatSupport.canWriteText(codec), name + " canWriteText");
      for (Format format : Format.values()) {
        RawValue raw = Codecs.encode(value, type, ctx, format);
        assertEquals(format, raw.getFormat(), name + " " + edge + " encode must keep " + format);
        Object back = Codecs.decode(raw, type, ctx, value.getClass());
        assertEquals(value, back, name + " " + edge + " round-trip in " + format);
      }
      checked++;
    }
    assertTrue(checked > 0, "expected at least one bindable edge case for " + name);
  }

  private static PgType builtinType(String name, int oid) {
    return new PgType(new ObjectName("pg_catalog", name), name, oid, 'b', 'N', -1, 0, 0, 0);
  }
}
