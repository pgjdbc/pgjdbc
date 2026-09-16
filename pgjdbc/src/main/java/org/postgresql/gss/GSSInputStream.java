/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.core.ProtocolViolationException;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.MessageProp;

import java.io.IOException;
import java.io.InputStream;

/**
 * Decrypts a GSS-encrypted packet stream, unwrapping one packet at a time through an
 * established {@link GSSContext}.
 *
 * <p>{@link #read(byte[], int, int)} does not block for the rest of a packet: once the wrapped
 * stream reports no bytes available, this method returns the bytes it has already decrypted, or
 * 0 when it has none. The {@link InputStream} contract allows 0 only for a zero length, so a
 * caller has to repeat the call. {@link #read()} repeats it itself.</p>
 */
public class GSSInputStream extends InputStream {
  /**
   * Largest declared length, in bytes, this stream accepts for an encrypted packet. The four
   * length bytes that carry the count are not part of it.
   *
   * <p>libpq ({@code fe-secure-gssapi.c} {@code pg_GSS_read}) and the backend
   * ({@code be-secure-gssapi.c} {@code be_gssapi_read}) both reject an encrypted packet
   * whose declared length exceeds {@code PQ_GSS_MAX_PACKET_SIZE - sizeof(uint32)}, and this
   * limit mirrors them. In both, the handshake that precedes this stream is limited by the
   * larger {@code PQ_GSS_AUTH_BUFFER_SIZE} instead.</p>
   */
  private static final int MAX_ENCRYPTED_PACKET_LENGTH = 16 * 1024 - 4;

  private final GSSContext gssContext;
  private final MessageProp messageProp;
  private final InputStream wrapped;
  // See https://www.postgresql.org/docs/current/protocol-flow.html#PROTOCOL-FLOW-GSSAPI
  // The server sends no encrypted packet larger than 16kB, and readLength refuses a
  // declared length over MAX_ENCRYPTED_PACKET_LENGTH, so this buffer holds every packet
  // the stream accepts and never has to grow.
  private final byte[] encrypted = new byte[16 * 1024];
  private int encryptedPos;
  private int encryptedLength;

  private byte @Nullable [] unencrypted;
  private int unencryptedPos;

  private final byte[] int4Buf = new byte[4];
  private int lenPos;

  private final byte[] int1Buf = new byte[1];

  public GSSInputStream(InputStream wrapped, GSSContext gssContext, MessageProp messageProp) {
    this.wrapped = wrapped;
    this.gssContext = gssContext;
    this.messageProp = messageProp;
  }

  @Override
  public int read() throws IOException {
    int res = 0;
    while (res == 0) {
      res = read(int1Buf);
    }
    return res == -1 ? -1 : int1Buf[0] & 0xFF;
  }

  @Override
  public int read(byte[] buffer, int pos, int len) throws IOException {
    int n = 0;
    // Server makes 16KiB frames, so we attempt several reads from the underlying stream
    // so we don't have to store the unencrypted buffer across GSSInputStream.read calls
    while (true) {
      // 1. Reading length from the wrapped stream
      if (lenPos < 4) {
        int res = readLength();
        if (res <= 0) {
          // Did not read "message length" fully, so we can't read encrypted message yet
          return n == 0 ? res : n;
        }
      }

      // 2. Reading encrypted message from the wrapped stream
      if (encryptedPos < encryptedLength) {
        int res = readEncryptedBytesAndUnwrap();
        if (res <= 0) {
          // Did not read encrypted message fully, so we can't deliver decrypted data yet
          return n == 0 ? res : n;
        }
      }

      // 3. Reading unencrypted message into the user-provided buffer
      byte[] unencrypted = castNonNull(this.unencrypted);
      int copyLength = Math.min(len - n, unencrypted.length - unencryptedPos);
      System.arraycopy(unencrypted, unencryptedPos, buffer, pos + n, copyLength);
      unencryptedPos += copyLength;
      n += copyLength;
      if (unencryptedPos == unencrypted.length) {
        // Start reading the new message on the next read
        lenPos = 0;
        encryptedPos = 0;
        this.unencrypted = null;
      }
      if (n >= len || wrapped.available() <= 0) {
        return n;
      }
    }
  }

  /**
   * Reads the declared length of the next encrypted packet into {@code encryptedLength}.
   *
   * @return -1 if end of stream reached, 0 if length is not fully read yet, and 1 if length is
   *     fully read
   * @throws IOException if the read fails, or if the declared length is outside
   *     1..{@value #MAX_ENCRYPTED_PACKET_LENGTH} bytes; in that case the wrapped stream is closed
   *     first, and a failure to close it is added to the exception as suppressed
   */
  private int readLength() throws IOException {
    while (true) {
      int res = wrapped.read(int4Buf, lenPos, 4 - lenPos);
      if (res == -1) {
        return -1;
      }
      lenPos += res;
      if (lenPos == 4) {
        break;
      }
      if (wrapped.available() <= 0) {
        // Did not read "message length" fully, and there's no more bytes available, so stop trying
        return 0;
      }
    }
    encryptedLength = ByteConverter.int4(int4Buf, 0);
    if (encryptedLength <= 0 || encryptedLength > MAX_ENCRYPTED_PACKET_LENGTH) {
      // Both byte counts go through String.valueOf so that they reach the message as plain digits.
      // MessageFormat formats an int with the grouping separators of the JVM locale, so an int
      // argument would vary by locale and fail to match a grep of the logs.
      IOException refusal = new ProtocolViolationException(GT.tr(
          "Protocol error. GSS encrypted packet has invalid length {0} (expected between 1 and {1} bytes).",
          String.valueOf(encryptedLength), String.valueOf(MAX_ENCRYPTED_PACKET_LENGTH)));
      // The stream is off a packet boundary and cannot be read further. Closing the wrapped
      // stream closes the socket, so the connection is closed even for a caller that catches the
      // IOException without aborting.
      try {
        wrapped.close();
      } catch (IOException e) {
        refusal.addSuppressed(e);
      }
      throw refusal;
    }
    return 1;
  }

  /**
   * Reads the encrypted message, and unwraps it.
   *
   * @return -1 of end of stream reached, 0 if the message is not fully read yet, and 1 if length is
   *     fully read
   * @throws IOException if read fails
   */
  private int readEncryptedBytesAndUnwrap() throws IOException {
    while (true) {
      int res = wrapped.read(encrypted, encryptedPos, encryptedLength - encryptedPos);
      if (res == -1) {
        // Should we raise something like "incomplete GSS message due to end of input stream"?
        return -1;
      }
      encryptedPos += res;
      if (encryptedPos == encryptedLength) {
        break;
      }
      if (wrapped.available() <= 0) {
        // The encrypted message is not yet ready, so we can't read user data yet
        return 0;
      }
    }
    try {
      this.unencrypted = gssContext.unwrap(encrypted, 0, encryptedLength, messageProp);
    } catch (GSSException e) {
      throw new IOException(e);
    }
    unencryptedPos = 0;
    return 1;
  }
}
