/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.CodecFormatSupport;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.RawValue;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.CodecRegistry;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * Locks the three format-capability layers to one shared truth. For a matrix of fake codecs with
 * every read/write capability combination, three things must agree: the {@link CodecFormatSupport}
 * predicates, the format {@link Codecs#encode}/{@link Codecs#decode} enforce for a requested format,
 * and the receive-format decision the driver's {@link CodecRegistry} makes. A drift-guard only
 * checks that a capability record matches an interface; this checks that the behaviour keyed off the
 * capability actually follows it — the F-3/F-4 failure class, where a dispatch site picks a format
 * without consulting the capability that governs it.
 *
 * <p>The write-negotiation half ({@code chooseBindFormat} with its text fallback) is a driver bind
 * policy and lands with the bind path; the receive half is already wired through
 * {@link CodecRegistry#canDecodeBinary}/{@link CodecRegistry#canDecodeText}, so it stands in for the
 * negotiate leg here.</p>
 */
class CodecFormatSupportDifferentialTest {

  /** A non-null value to encode; the fakes ignore it, so any reference is fine. */
  private static final Object VALUE = "v";

  static List<Case> cases() {
    return Arrays.asList(
        // A bare Codec reads and writes neither format.
        new Case("fmtdiff_neither", 995_001, new FakeNeither("fmtdiff_neither"),
            false, false, false, false),
        // Reads binary, cannot produce a binary payload (the read-only-binary shape).
        new Case("fmtdiff_bin_read_only", 995_002,
            new FakeBinary("fmtdiff_bin_read_only", true, false, false),
            true, false, false, false),
        // Writes binary, does not read binary (the money shape).
        new Case("fmtdiff_bin_write_only", 995_003,
            new FakeBinary("fmtdiff_bin_write_only", false, true, true),
            false, true, false, false),
        // Binary both ways.
        new Case("fmtdiff_bin_all", 995_004,
            new FakeBinary("fmtdiff_bin_all", true, true, true),
            true, true, false, false),
        // encodesBinary() is true, but the value-level canEncodeBinary() rejects this value, so the
        // write capability must follow the value-level answer, not the type-level flag.
        new Case("fmtdiff_bin_value_gated", 995_005,
            new FakeBinary("fmtdiff_bin_value_gated", true, true, false),
            true, false, false, false),
        // Text both ways.
        new Case("fmtdiff_text_read", 995_006, new FakeText("fmtdiff_text_read", true),
            false, false, true, true),
        // Implements TextCodec (so text write is available) but does not read text.
        new Case("fmtdiff_text_no_read", 995_007, new FakeText("fmtdiff_text_no_read", false),
            false, false, false, true),
        // Both interfaces, every capability on.
        new Case("fmtdiff_both_all", 995_008,
            new FakeBoth("fmtdiff_both_all", true, true, true),
            true, true, true, true),
        // Both interfaces, reads neither and cannot write binary; only text write survives.
        new Case("fmtdiff_both_read_none", 995_009,
            new FakeBoth("fmtdiff_both_read_none", false, false, false),
            false, false, false, true),
        // Contradictory codec: encodesBinary()=false but canEncodeBinary()=true. canWriteBinary must
        // follow the type-level flag and stay false, not trust the lax value-level override.
        new Case("fmtdiff_bin_encodes_false_can_true", 995_010,
            new FakeBinary("fmtdiff_bin_encodes_false_can_true", true, false, true),
            true, false, false, false));
  }

  @ParameterizedTest
  @MethodSource("cases")
  void predicatesMatchDeclaredCapability(Case c) throws SQLException {
    CodecContext ctx = new SingleCodecContext(c.codec);
    assertEquals(c.readsBinary, CodecFormatSupport.canReadBinary(c.codec), "canReadBinary");
    assertEquals(c.writesBinary,
        CodecFormatSupport.canWriteBinary(c.codec, VALUE, c.type, ctx), "canWriteBinary");
    assertEquals(c.readsText, CodecFormatSupport.canReadText(c.codec), "canReadText");
    assertEquals(c.writesText, CodecFormatSupport.canWriteText(c.codec), "canWriteText");
  }

  @ParameterizedTest
  @MethodSource("cases")
  void codecsEnforcesRequestedFormat(Case c) {
    CodecContext ctx = new SingleCodecContext(c.codec);

    assertEncodeMatches(c.writesBinary, c.type, ctx, Format.BINARY);
    assertEncodeMatches(c.writesText, c.type, ctx, Format.TEXT);

    RawValue binary = RawValue.binary(new byte[]{1});
    assertDecodeMatches(c.readsBinary, c.type, ctx, binary, "decode binary");
    RawValue text = RawValue.text("t".getBytes(StandardCharsets.UTF_8));
    assertDecodeMatches(c.readsText, c.type, ctx, text, "decode text");
  }

  @ParameterizedTest
  @MethodSource("cases")
  void registryReceiveNegotiationMatchesReadCapability(Case c) {
    CodecRegistry registry = new CodecRegistry();
    registry.registerByName(c.codec);
    assertSame(c.codec, registry.getByOid(c.type.getOid(), c.type),
        "the fake resolves for its own type");
    assertEquals(c.readsBinary, registry.canDecodeBinary(c.type.getOid(), c.type), "canDecodeBinary");
    assertEquals(c.readsText, registry.canDecodeText(c.type.getOid(), c.type), "canDecodeText");
  }

  @ParameterizedTest
  @MethodSource("cases")
  void chooseBindFormatFollowsWriteCapability(Case c) throws SQLException {
    CodecContext ctx = new SingleCodecContext(c.codec);

    // With the backend allowing binary, prefer binary when the codec can write the value in binary,
    // else text, else refuse — never silently pick a format the codec cannot produce.
    if (c.writesBinary) {
      assertEquals(Format.BINARY,
          CodecFormatPolicy.chooseBindFormat(c.codec, VALUE, c.type, ctx, true),
          "chooseBindFormat(backendCanBinary=true) must prefer binary when the value is writable");
    } else if (c.writesText) {
      assertEquals(Format.TEXT,
          CodecFormatPolicy.chooseBindFormat(c.codec, VALUE, c.type, ctx, true),
          "chooseBindFormat must fall back to text when binary is not writable");
    } else {
      assertThrows(SQLException.class,
          () -> CodecFormatPolicy.chooseBindFormat(c.codec, VALUE, c.type, ctx, true),
          "chooseBindFormat must refuse when the codec writes no format");
    }

    // With the backend refusing binary, the choice is never binary.
    if (c.writesText) {
      assertEquals(Format.TEXT,
          CodecFormatPolicy.chooseBindFormat(c.codec, VALUE, c.type, ctx, false),
          "chooseBindFormat(backendCanBinary=false) must never return binary");
    } else {
      assertThrows(SQLException.class,
          () -> CodecFormatPolicy.chooseBindFormat(c.codec, VALUE, c.type, ctx, false),
          "chooseBindFormat must refuse when text is unavailable and binary is off");
    }
  }

  @Test
  void canWriteTextIsExactlyTextCodecMembership() {
    // Deliberate contract: there is no writesText() opt-out, so text encoding is available for every
    // TextCodec (including a read-only-text one) and for no non-TextCodec.
    assertTrue(CodecFormatSupport.canWriteText(new FakeText("fmtdiff_t_rw", true)),
        "a text codec writes text");
    assertTrue(CodecFormatSupport.canWriteText(new FakeText("fmtdiff_t_ro", false)),
        "a read-only text codec still writes text by contract");
    assertFalse(CodecFormatSupport.canWriteText(new FakeBinary("fmtdiff_b", true, true, true)),
        "a binary-only codec does not write text");
    assertFalse(CodecFormatSupport.canWriteText(new FakeNeither("fmtdiff_n")),
        "a bare codec does not write text");
  }

  private static void assertEncodeMatches(boolean capable, TypeDescriptor type, CodecContext ctx,
      Format format) {
    if (capable) {
      RawValue encoded = assertDoesNotThrow(() -> Codecs.encode(VALUE, type, ctx, format),
          "encode " + format + " must succeed when the codec can write it");
      assertEquals(format, encoded.getFormat(),
          "encode " + format + " must keep the requested format, never fall back");
    } else {
      assertThrows(SQLException.class, () -> Codecs.encode(VALUE, type, ctx, format),
          "encode " + format + " must refuse when the codec cannot write it");
    }
  }

  private static void assertDecodeMatches(boolean capable, TypeDescriptor type, CodecContext ctx,
      RawValue value, String label) {
    if (capable) {
      assertDoesNotThrow(() -> Codecs.decode(value, type, ctx, String.class),
          label + " must succeed when the codec can read the format");
    } else {
      assertThrows(SQLException.class, () -> Codecs.decode(value, type, ctx, String.class),
          label + " must refuse when the codec cannot read the format");
    }
  }

  /** One codec profile plus its hand-declared capabilities, the truth the three layers agree with. */
  private static final class Case {
    final PgType type;
    final Codec codec;
    final boolean readsBinary;
    final boolean writesBinary;
    final boolean readsText;
    final boolean writesText;
    private final String name;

    Case(String name, int oid, Codec codec,
        boolean readsBinary, boolean writesBinary, boolean readsText, boolean writesText) {
      this.name = name;
      this.type = new PgType(new ObjectName("public", name), name, oid, 'b', 'U', -1, 0, 0, 0);
      this.codec = codec;
      this.readsBinary = readsBinary;
      this.writesBinary = writesBinary;
      this.readsText = readsText;
      this.writesText = writesText;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private static final class FakeNeither implements Codec {
    private final String typeName;

    FakeNeither(String typeName) {
      this.typeName = typeName;
    }

    @Override
    public String getPrimaryTypeName() {
      return typeName;
    }

    @Override
    public Class<?> getDefaultJavaType() {
      return String.class;
    }
  }

  private static final class FakeBinary implements BinaryCodec {
    private final String typeName;
    private final boolean readsBinary;
    private final boolean encodesBinary;
    private final boolean canEncodeValue;

    FakeBinary(String typeName, boolean readsBinary, boolean encodesBinary, boolean canEncodeValue) {
      this.typeName = typeName;
      this.readsBinary = readsBinary;
      this.encodesBinary = encodesBinary;
      this.canEncodeValue = canEncodeValue;
    }

    @Override
    public String getPrimaryTypeName() {
      return typeName;
    }

    @Override
    public Class<?> getDefaultJavaType() {
      return String.class;
    }

    @Override
    public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
        CodecContext ctx) {
      return "decoded";
    }

    @Override
    public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) {
      return new byte[]{1};
    }

    @Override
    public boolean decodesBinary() {
      return readsBinary;
    }

    @Override
    public boolean encodesBinary() {
      return encodesBinary;
    }

    @Override
    public boolean canEncodeBinary(Object value, TypeDescriptor type, CodecContext ctx) {
      return canEncodeValue;
    }
  }

  private static final class FakeText implements TextCodec {
    private final String typeName;
    private final boolean readsText;

    FakeText(String typeName, boolean readsText) {
      this.typeName = typeName;
      this.readsText = readsText;
    }

    @Override
    public String getPrimaryTypeName() {
      return typeName;
    }

    @Override
    public Class<?> getDefaultJavaType() {
      return String.class;
    }

    @Override
    public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) {
      return "decoded";
    }

    @Override
    public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) {
      return "t";
    }

    @Override
    public boolean decodesText() {
      return readsText;
    }
  }

  private static final class FakeBoth implements BinaryCodec, TextCodec {
    private final String typeName;
    private final boolean readsBinary;
    private final boolean encodesBinary;
    private final boolean readsText;

    FakeBoth(String typeName, boolean readsBinary, boolean encodesBinary, boolean readsText) {
      this.typeName = typeName;
      this.readsBinary = readsBinary;
      this.encodesBinary = encodesBinary;
      this.readsText = readsText;
    }

    @Override
    public String getPrimaryTypeName() {
      return typeName;
    }

    @Override
    public Class<?> getDefaultJavaType() {
      return String.class;
    }

    @Override
    public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
        CodecContext ctx) {
      return "decoded";
    }

    @Override
    public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) {
      return new byte[]{1};
    }

    @Override
    public boolean decodesBinary() {
      return readsBinary;
    }

    @Override
    public boolean encodesBinary() {
      return encodesBinary;
    }

    @Override
    public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) {
      return "decoded";
    }

    @Override
    public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) {
      return "t";
    }

    @Override
    public boolean decodesText() {
      return readsText;
    }
  }

}
