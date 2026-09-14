/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.util.FakeSocket;
import org.postgresql.util.GT;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A C-string reader on {@link PGStream} reads a string whose NUL lies within the bytes the open
 * message has left and within {@link PGStream#MAX_CSTRING_LENGTH}, and rejects any other string
 * with an exception that marks the stream broken. Inside a message,
 * {@link ProtocolHardeningMode#DISABLE} raises the per-string limit so that the message alone
 * bounds the string; with no message open, the limit holds in every mode.
 *
 * <p>Each test feeds hand-built protocol bytes through a socket with no server behind it: a
 * 4-byte length that counts itself, then the body, then whatever follows the message on the
 * stream.</p>
 */
class PGStreamCStringTest {

  private static final String MESSAGE = "CStringProbe";

  /**
   * The three public readers that scan for a NUL. Each must apply the same bound.
   */
  enum Reader {
    RECEIVE_STRING {
      @Override
      String read(PGStream stream) throws IOException {
        return stream.receiveString();
      }
    },
    RECEIVE_CANONICAL_STRING {
      @Override
      String read(PGStream stream) throws IOException {
        return stream.receiveCanonicalString();
      }
    },
    RECEIVE_CANONICAL_STRING_IF_PRESENT {
      @Override
      String read(PGStream stream) throws IOException {
        return stream.receiveCanonicalStringIfPresent();
      }
    };

    abstract String read(PGStream stream) throws IOException;
  }

  @ParameterizedTest
  @EnumSource(Reader.class)
  void stringsEndingWithinAndOnTheLastByteOfTheMessageAreRead(Reader reader) throws IOException {
    try (PGStream stream = openStream(bytes(0, 0, 0, 11, "abc\0de\0"))) {
      stream.readMessageLength(MESSAGE, 4);
      String first = reader.read(stream);
      String second = reader.read(stream);
      stream.endMessage();
      assertAll(
          () -> assertEquals("abc", first, "first string"),
          () -> assertEquals("de", second, "string ending on the last body byte"),
          () -> assertFalse(stream.isClosed(), "isClosed() after reading a valid message"));
    }
  }

  /**
   * The body is {@code "abcd"} and the NUL is the first byte after the message, so the string
   * needs 5 bytes where the message has 4 left.
   */
  @ParameterizedTest
  @EnumSource(Reader.class)
  void aStringWhoseNulIsOneBytePastTheMessageIsRejectedAndBreaksTheStream(Reader reader)
      throws IOException {
    try (PGStream stream = openStream(bytes(0, 0, 0, 8, "abcd\0"))) {
      stream.readMessageLength(MESSAGE, 4);
      IOException e = assertThrowsExactly(ProtocolViolationException.class, () -> reader.read(stream));
      assertAll(
          () -> assertEquals(
              GT.tr("Protocol error. C-string in {0} message of {1} bytes exceeds remaining budget of {2} bytes.",
                  MESSAGE, "8", "4"),
              e.getMessage()),
          () -> assertTrue(stream.isClosed(), "isClosed() after the rejected string"));
    }
  }

  /**
   * Bytes after the message on the stream do not extend the bound, however far away the NUL in
   * them is, and no {@link ProtocolHardeningMode} lifts the bound.
   */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aStringContinuingIntoTheNextMessageIsRejected(ProtocolHardeningMode mode) throws IOException {
    try (PGStream stream = openStream(bytes(0, 0, 0, 7, "abc", "Z", 0, 0, 0, 5, "\0"))) {
      stream.setProtocolHardeningMode(mode);
      stream.readMessageLength(MESSAGE, 4);
      IOException e = assertThrowsExactly(ProtocolViolationException.class, stream::receiveString);
      assertAll(
          () -> assertEquals(
              GT.tr("Protocol error. C-string in {0} message of {1} bytes exceeds remaining budget of {2} bytes.",
                  MESSAGE, "7", "3"),
              e.getMessage()),
          () -> assertTrue(stream.isClosed(), "isClosed() after the rejected string"));
    }
  }

  /**
   * No {@link ProtocolHardeningMode} lets a scan start in a message whose body is consumed.
   */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aStringInAMessageWithNoBytesLeftIsRejectedAndBreaksTheStream(ProtocolHardeningMode mode)
      throws IOException {
    try (PGStream stream = openStream(bytes(0, 0, 0, 7, "ab\0", "x\0"))) {
      stream.setProtocolHardeningMode(mode);
      stream.readMessageLength(MESSAGE, 4);
      stream.receiveString();
      IOException e = assertThrowsExactly(ProtocolViolationException.class, stream::receiveString);
      assertAll(
          () -> assertEquals(
              GT.tr("Protocol error. {0} message of {1} bytes has no room left for a C-string (remaining budget: {2} bytes).",
                  MESSAGE, "7", "0"),
              e.getMessage()),
          () -> assertTrue(stream.isClosed(), "isClosed() after the rejected string"));
    }
  }

  @Test
  void endOfStreamInsideAStringBreaksTheStream() throws IOException {
    try (PGStream stream = openStream(bytes(0, 0, 0, 100, "abc"))) {
      stream.readMessageLength(MESSAGE, 4);
      assertThrowsExactly(EOFException.class, stream::receiveString);
      assertTrue(stream.isClosed(), "isClosed() after end of stream inside a string");
    }
  }

  @Test
  void withNoMessageOpenAStringIsReadUpToItsNul() throws IOException {
    try (PGStream stream = openStream(bytes("abc\0x"))) {
      String value = stream.receiveString();
      assertAll(
          () -> assertEquals("abc", value, "receiveString()"),
          () -> assertEquals('x', stream.receiveChar(), "byte after the string"),
          () -> assertFalse(stream.isClosed(), "isClosed() after reading the string"));
    }
  }

  /**
   * A string whose NUL is byte 1048576, the last byte {@link PGStream#MAX_CSTRING_LENGTH} allows, is
   * read in every mode inside a message that has bytes left after it.
   */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aStringOfTheLimitWithItsNulIsReadInAMessageThatAllowsMore(ProtocolHardeningMode mode)
      throws IOException {
    // 4 (length) + 1048575 letters + NUL + "b" + NUL
    try (PGStream stream = openStream(bytes(0, 0x10, 0, 6, letters(1048575), "\0b\0"))) {
      stream.setProtocolHardeningMode(mode);
      stream.readMessageLength(MESSAGE, 4);
      String first = stream.receiveString();
      String second = stream.receiveString();
      stream.endMessage();
      assertAll(
          () -> assertEquals(1048575, first.length(), "length of the string at the limit"),
          () -> assertEquals("b", second, "string after it"),
          () -> assertFalse(stream.isClosed(), "isClosed() after reading the message"));
    }
  }

  /**
   * A string whose NUL is byte 1048577 is refused, although the message has room for it. The error
   * offers {@code disable} as the remedy, because that mode lifts this limit inside a message.
   */
  @ParameterizedTest
  @EnumSource(Reader.class)
  void aStringOneBytePastTheLimitIsRejectedNamingDisableAsTheRemedyAndBreaksTheStream(Reader reader)
      throws IOException {
    // 4 (length) + 1048576 letters + NUL + "b" + NUL = 1048583
    try (PGStream stream = openStream(bytes(0, 0x10, 0, 7, letters(1048576), "\0b\0"))) {
      stream.setProtocolHardeningMode(ProtocolHardeningMode.FAIL);
      stream.readMessageLength(MESSAGE, 4);
      IOException e = assertThrowsExactly(ProtocolViolationException.class, () -> reader.read(stream));
      assertAll(
          () -> assertEquals(
              GT.tr("Protocol error. C-string in {0} message of {1} bytes exceeds the pgjdbc limit of {2} bytes on a single C-string. Set -D{3}=disable to skip these limits altogether.",
                  MESSAGE, "1048583", "1048576", "pgjdbc.protocolHardeningMode"),
              e.getMessage()),
          () -> assertTrue(stream.isClosed(), "isClosed() after the rejected string"));
    }
  }

  /**
   * Under {@link ProtocolHardeningMode#DISABLE} the message is the only bound on a string inside
   * it, so a string one byte past {@link PGStream#MAX_CSTRING_LENGTH} is read.
   */
  @Test
  void underDisableAStringOneBytePastTheLimitIsReadWhenTheMessageAllowsIt() throws IOException {
    try (PGStream stream = openStream(bytes(0, 0x10, 0, 7, letters(1048576), "\0b\0"))) {
      stream.setProtocolHardeningMode(ProtocolHardeningMode.DISABLE);
      stream.readMessageLength(MESSAGE, 4);
      String first = stream.receiveString();
      String second = stream.receiveString();
      stream.endMessage();
      assertAll(
          () -> assertEquals(1048576, first.length(), "length of the string past the limit"),
          () -> assertEquals("b", second, "string after it"),
          () -> assertFalse(stream.isClosed(), "isClosed() after reading the message"));
    }
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void withNoMessageOpenAStringOfTheLimitWithItsNulIsRead(ProtocolHardeningMode mode)
      throws IOException {
    try (PGStream stream = openStream(bytes(letters(1048575), "\0x"))) {
      stream.setProtocolHardeningMode(mode);
      String value = stream.receiveString();
      assertAll(
          () -> assertEquals(1048575, value.length(), "length of the string at the limit"),
          () -> assertEquals('x', stream.receiveChar(), "byte after the string"),
          () -> assertFalse(stream.isClosed(), "isClosed() after reading the string"));
    }
  }

  /**
   * With no message open nothing but {@link PGStream#MAX_CSTRING_LENGTH} bounds the scan, so no
   * mode lifts it, and the error offers no remedy.
   */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void withNoMessageOpenAStringOneBytePastTheLimitIsRejectedInEveryMode(ProtocolHardeningMode mode)
      throws IOException {
    try (PGStream stream = openStream(bytes(letters(1048576), "\0x"))) {
      stream.setProtocolHardeningMode(mode);
      IOException e = assertThrowsExactly(ProtocolViolationException.class, stream::receiveString);
      assertAll(
          () -> assertEquals(
              GT.tr("Protocol error. A C-string read outside a tracked message exceeds the pgjdbc limit of {0} bytes on a single C-string.",
                  "1048576"),
              e.getMessage()),
          () -> assertTrue(stream.isClosed(), "isClosed() after the rejected string"));
    }
  }

  /**
   * The scan keeps the string in the read buffer, so the limit on the string is what stops the
   * buffer from growing. A message that declares 64 MB (64000000 bytes) and sends no NUL is refused
   * after the stream has read at most the 4-byte length and a 2 MiB (2097152 bytes) buffer from the
   * socket.
   */
  @Test
  void aScanRefusedAtTheLimitReadsNoMoreThanATwoMiBBufferFromTheSocket() throws IOException {
    CountingNoNulStream source = new CountingNoNulStream(new byte[]{0x03, (byte) 0xd0, (byte) 0x90, 0x00});
    try (PGStream stream = PGStreamTestSupport.openStream(new FakeSocket(source))) {
      stream.setProtocolHardeningMode(ProtocolHardeningMode.FAIL);
      assertEquals(64000000, stream.readMessageLength(MESSAGE, 4), "declared length");
      assertThrowsExactly(ProtocolViolationException.class, stream::receiveString);
      long served = source.served;
      assertAll(
          () -> assertTrue(served <= 4 + 2097152, () -> "bytes read from the socket: " + served),
          () -> assertTrue(stream.isClosed(), "isClosed() after the rejected string"));
    }
  }

  /**
   * A string read before authentication has only its message to bound it under
   * {@link ProtocolHardeningMode#DISABLE}. Every pre-authentication message the driver scans for
   * C-strings, NegotiateProtocolVersion and AuthenticationRequest with its SASL mechanism names, is
   * therefore limited to at most {@link PGStream#MAX_CSTRING_LENGTH} bytes, so no string in it can
   * exceed that limit.
   */
  @Test
  void everyPreAuthenticationMessageScannedForCStringsFitsTheCStringLimit() {
    assertAll(
        () -> assertTrue(PGStream.MAX_NEGOTIATE_PROTOCOL_VERSION_SIZE <= PGStream.MAX_CSTRING_LENGTH,
            () -> "MAX_NEGOTIATE_PROTOCOL_VERSION_SIZE " + PGStream.MAX_NEGOTIATE_PROTOCOL_VERSION_SIZE
                + " <= MAX_CSTRING_LENGTH " + PGStream.MAX_CSTRING_LENGTH),
        () -> assertTrue(PGStream.MAX_AUTHENTICATION_MESSAGE_SIZE <= PGStream.MAX_CSTRING_LENGTH,
            () -> "MAX_AUTHENTICATION_MESSAGE_SIZE " + PGStream.MAX_AUTHENTICATION_MESSAGE_SIZE
                + " <= MAX_CSTRING_LENGTH " + PGStream.MAX_CSTRING_LENGTH));
  }

  /**
   * The C-string limit stops the buffer only while it is the smaller bound on the scan, so it must
   * stay below the default {@code maxServerTextMessageSize}, which bounds a ParameterStatus.
   */
  @Test
  void theCStringLimitIsBelowTheDefaultServerTextMessageLimit() {
    assertTrue(PGStream.MAX_CSTRING_LENGTH < PGStream.DEFAULT_MAX_SERVER_TEXT_MESSAGE_SIZE,
        () -> "MAX_CSTRING_LENGTH " + PGStream.MAX_CSTRING_LENGTH
            + " < DEFAULT_MAX_SERVER_TEXT_MESSAGE_SIZE " + PGStream.DEFAULT_MAX_SERVER_TEXT_MESSAGE_SIZE);
  }

  /** {@code count} bytes {@code 'a'}. */
  private static byte[] letters(int count) {
    byte[] b = new byte[count];
    Arrays.fill(b, (byte) 'a');
    return b;
  }

  /**
   * Serves a fixed prefix, then up to 64 MB of {@code 'x'} with no NUL, then end of stream, and
   * counts the bytes served.
   */
  private static final class CountingNoNulStream extends InputStream {
    private static final int LIMIT = 64_000_000;
    private final byte[] prefix;
    long served;

    CountingNoNulStream(byte[] prefix) {
      this.prefix = prefix;
    }

    @Override
    public int read() {
      byte[] one = new byte[1];
      return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) {
      if (len == 0) {
        return 0;
      }
      if (served >= prefix.length + (long) LIMIT) {
        return -1;
      }
      int n = 0;
      while (n < len && served < prefix.length) {
        b[off + n++] = prefix[(int) served++];
      }
      int rest = (int) Math.min(len - n, prefix.length + (long) LIMIT - served);
      Arrays.fill(b, off + n, off + n + rest, (byte) 'x');
      served += rest;
      return n + rest;
    }
  }

  /**
   * Concatenates the parts into one array: an {@link Integer} is one byte, a {@link String} its
   * ASCII bytes, a {@code byte[]} itself.
   */
  private static byte[] bytes(Object... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Object part : parts) {
      if (part instanceof Integer) {
        out.write((Integer) part);
      } else if (part instanceof byte[]) {
        byte[] b = (byte[]) part;
        out.write(b, 0, b.length);
      } else {
        byte[] b = ((String) part).getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
      }
    }
    return out.toByteArray();
  }

  private static PGStream openStream(byte[] input) {
    return PGStreamTestSupport.openStream(new FakeSocket(input));
  }
}
