/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.util.ByteConverter;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.MessageProp;

import java.io.IOException;
import java.io.InputStream;

public class GSSInputStream extends InputStream {
  private final GSSContext gssContext;
  private final MessageProp messageProp;
  private final InputStream wrapped;
  // See https://www.postgresql.org/docs/current/protocol-flow.html#PROTOCOL-FLOW-GSSAPI
  // The server can be expected to not send encrypted packets of larger than 16kB to the client
  private byte[] encrypted = new byte[16 * 1024];
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
   * Reads the length of the wrapper message.
   *
   * @return -1 of end of stream reached, 0 if length is not fully read yet, and 1 if length is
   *     fully read
   * @throws IOException if read fails
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
    if (encrypted.length < encryptedLength) {
      // If the buffer is too small, reallocate
      encrypted = new byte[encryptedLength];
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
