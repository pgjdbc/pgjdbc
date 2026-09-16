/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.gss;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.ProtocolViolationException;
import org.postgresql.gss.GSSInputStream;
import org.postgresql.test.util.Wire;
import org.postgresql.util.GT;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.ietf.jgss.MessageProp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * An encrypted packet declares 1 to 16380 bytes, and {@link GSSInputStream} refuses any other
 * declared length with an {@link IOException} before it decrypts the packet. The refusal closes
 * the wrapped stream, which for a socket's input stream closes the socket.
 *
 * <p>The context here unwraps a packet to its own bytes, so the plaintext a read returns is the
 * packet body the test wrote.</p>
 */
class GSSInputStreamPacketLengthTest {
  private static final int MAX_PACKET_LENGTH = 16380;

  private static GSSInputStream streamOver(byte[] packets) {
    return streamOver(new RecordingSource(packets));
  }

  private static GSSInputStream streamOver(RecordingSource source) {
    MessageProp messageProp = new MessageProp(0, true);
    return new GSSInputStream(source, new IdentityContext(messageProp), messageProp);
  }

  private static byte[] readToEnd(GSSInputStream stream) throws IOException {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int n;
    while ((n = stream.read(buffer)) != -1) {
      result.write(buffer, 0, n);
    }
    return result.toByteArray();
  }

  @Test
  void aPacketOfOneByteIsDecrypted() throws IOException {
    GSSInputStream stream = streamOver(new Wire().int4(1).int1('x').toBytes());

    assertAll(
        () -> assertEquals('x', stream.read(), "first read()"),
        () -> assertEquals(-1, stream.read(), "read() after the packet"));
  }

  @Test
  void aPacketOf16380BytesIsDecrypted() throws IOException {
    RecordingSource source = new RecordingSource(
        new Wire().int4(MAX_PACKET_LENGTH).bytes(MAX_PACKET_LENGTH).toBytes());
    GSSInputStream stream = streamOver(source);

    byte[] plaintext = readToEnd(stream);

    assertAll(
        () -> assertArrayEquals(new Wire().bytes(MAX_PACKET_LENGTH).toBytes(), plaintext),
        () -> assertFalse(source.closed, "wrapped stream closed"));
  }

  /**
   * The read asks for one byte more than the first packet holds, so it has to go on to decrypt
   * the second packet.
   */
  @Test
  void aReadThatSpansA16380BytePacketAndAOneBytePacketReturnsBoth() throws IOException {
    GSSInputStream stream = streamOver(new Wire()
        .int4(MAX_PACKET_LENGTH).bytes(MAX_PACKET_LENGTH)
        .int4(1).int1('z')
        .toBytes());
    byte[] buffer = new byte[MAX_PACKET_LENGTH + 1];

    int n = stream.read(buffer, 0, buffer.length);

    byte[] expected = new Wire().bytes(MAX_PACKET_LENGTH).int1('z').toBytes();
    assertAll(
        () -> assertEquals(MAX_PACKET_LENGTH + 1, n, "read(buffer, 0, 16381)"),
        () -> assertArrayEquals(expected, buffer));
  }

  /**
   * For 16381 the packet body follows the declared length in full, so a stream that skipped the
   * check would decrypt it rather than fail on end of stream. The extremes send the length alone,
   * and {@code Integer.MAX_VALUE} catches a check that adds the four length bytes and overflows.
   * The expected text is built from the digits alone, so an argument formatted with locale
   * grouping separators fails the comparison.
   */
  @ParameterizedTest
  @CsvSource({"0, 0", "-1, 0", "16381, 16381", "2147483647, 0", "-2147483648, 0"})
  void aDeclaredLengthOutside1To16380IsRefusedAndClosesTheWrappedStream(int declaredLength,
      int bodyLength) {
    RecordingSource source = new RecordingSource(
        new Wire().int4(declaredLength).bytes(bodyLength).toBytes());
    GSSInputStream stream = streamOver(source);

    IOException e = assertThrowsExactly(ProtocolViolationException.class, () -> readToEnd(stream));

    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. GSS encrypted packet has invalid length {0} (expected between 1 and {1} bytes).",
                String.valueOf(declaredLength), "16380"),
            e.getMessage()),
        () -> assertTrue(source.closed, "wrapped stream closed"));
  }

  /** A failure to close the wrapped stream is kept on the refusal, which is still thrown. */
  @Test
  void aWrappedStreamThatFailsToCloseLeavesTheRefusalWithTheCloseFailureSuppressed() {
    RecordingSource source = new RecordingSource(new Wire().int4(0).toBytes());
    IOException closeFailure = new IOException("close failed");
    source.closeFailure = closeFailure;
    GSSInputStream stream = streamOver(source);

    IOException e = assertThrowsExactly(ProtocolViolationException.class, () -> readToEnd(stream));

    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. GSS encrypted packet has invalid length {0} (expected between 1 and {1} bytes).",
                "0", "16380"),
            e.getMessage()),
        () -> assertArrayEquals(new Throwable[]{closeFailure}, e.getSuppressed(), "suppressed"));
  }

  /** Serves the given bytes and records {@code close()}, which throws {@link #closeFailure} if set. */
  private static final class RecordingSource extends ByteArrayInputStream {
    boolean closed;
    @Nullable IOException closeFailure;

    RecordingSource(byte[] bytes) {
      super(bytes);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      IOException failure = closeFailure;
      if (failure != null) {
        throw failure;
      }
    }
  }

  /**
   * Unwraps a packet to a copy of its bytes.
   */
  private static final class IdentityContext extends MockGSSContext {
    IdentityContext(MessageProp messageProp) {
      super(0, messageProp);
    }

    @Override
    public byte[] unwrap(byte[] inBuf, int offset, int len, MessageProp msgProp) {
      return Arrays.copyOfRange(inBuf, offset, offset + len);
    }
  }
}
