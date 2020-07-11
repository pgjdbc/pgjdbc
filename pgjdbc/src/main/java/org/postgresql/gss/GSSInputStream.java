/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.MessageProp;

import java.io.IOException;
import java.io.InputStream;

public class GSSInputStream extends InputStream {
  private GSSContext gssContext;
  private MessageProp messageProp;
  private InputStream wrapped;
  byte @Nullable [] unencrypted;
  int unencryptedPos;
  int unencryptedLength;

  public GSSInputStream( InputStream wrapped, GSSContext gssContext, MessageProp messageProp) {
    this.wrapped = wrapped;
    this.gssContext = gssContext;
    this.messageProp = messageProp;
  }

  @Override
  public int read() throws IOException {
    return 0;
  }

  @Override
  public int read(byte [] buffer, int pos, int len) throws IOException {
    byte[] int4Buf = new byte[4];
    int encryptedLength;
    int copyLength = 0;

    if ( unencryptedLength > 0 ) {
      copyLength = Math.min(len, unencryptedLength);
      System.arraycopy(castNonNull(unencrypted), unencryptedPos, buffer, pos, copyLength);
      unencryptedLength -= copyLength;
      unencryptedPos += copyLength;
    } else {
      if (wrapped.read(int4Buf, 0, 4) == 4 ) {

        encryptedLength = ((int4Buf[0] & 0xFF) << 24 | (int4Buf[1] & 0xFF) << 16 | (int4Buf[2] & 0xFF) << 8
            | int4Buf[3] & 0xFF);

        byte[] encryptedBuffer = new byte[encryptedLength];
        wrapped.read(encryptedBuffer, 0, encryptedLength);

        try {
          byte[] unencrypted = gssContext.unwrap(encryptedBuffer, 0, encryptedLength, messageProp);
          this.unencrypted = unencrypted;
          unencryptedLength = unencrypted.length;
          unencryptedPos = 0;

          copyLength = Math.min(len, unencrypted.length);
          System.arraycopy(unencrypted, unencryptedPos, buffer, pos, copyLength);
          unencryptedLength -= copyLength;
          unencryptedPos += copyLength;

        } catch (GSSException e) {
          throw new IOException(e);
        }
        return copyLength;
      }
    }
    return copyLength;
  }
}
