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
import java.nio.charset.StandardCharsets;

/**
 * A C-string reader on {@link PGStream} reads a string whose NUL lies within the bytes the open
 * message has left, and rejects any other string with an exception that marks the stream broken.
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
      IOException e = assertThrowsExactly(IOException.class, () -> reader.read(stream));
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
      IOException e = assertThrowsExactly(IOException.class, stream::receiveString);
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
      IOException e = assertThrowsExactly(IOException.class, stream::receiveString);
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
   * Concatenates the parts into one array: an {@link Integer} is one byte, a {@link String} its
   * ASCII bytes.
   */
  private static byte[] bytes(Object... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Object part : parts) {
      if (part instanceof Integer) {
        out.write((Integer) part);
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
