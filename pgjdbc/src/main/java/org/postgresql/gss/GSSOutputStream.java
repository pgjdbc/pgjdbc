/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import org.postgresql.util.internal.PgBufferedOutputStream;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.MessageProp;

import java.io.IOException;

/**
 * Output stream that wraps each packed with GSS encryption.
 */
public class GSSOutputStream extends PgBufferedOutputStream {
  private final PgBufferedOutputStream pgOut;
  private final GSSContext gssContext;
  private final MessageProp messageProp;

  /**
   * Creates GSS output stream.
   * @param out output stream for the encrypted data
   * @param gssContext gss context
   * @param messageProp message properties
   * @param maxTokenSize maximum length of the encrypted messages
   */
  public GSSOutputStream(PgBufferedOutputStream out, GSSContext gssContext, MessageProp messageProp, int maxTokenSize) throws GSSException {
    super(out, getBufferSize(gssContext, messageProp, maxTokenSize));
    this.pgOut = out;
    this.gssContext = gssContext;
    this.messageProp = messageProp;
  }

  private static int getBufferSize(GSSContext gssContext, MessageProp messageProp, int maxTokenSize) throws GSSException {
    return gssContext.getWrapSizeLimit(messageProp.getQOP(), messageProp.getPrivacy(), maxTokenSize);
  }

  @Override
  protected void flushBuffer() throws IOException {
    if (count > 0) {
      writeWrapped(buf, 0, count);
      count = 0;
    }
  }

  private void writeWrapped(byte[] b, int off, int len) throws IOException {
    try {
      byte[] token = gssContext.wrap(b, off, len, messageProp);
      pgOut.writeInt4(token.length);
      pgOut.write(token, 0, token.length);
    } catch (GSSException ex) {
      throw new IOException(ex);
    }
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    if (count > 0) {
      // If there's some data in the buffer, combine both
      int avail = buf.length - count;
      int prefixLength = Math.min(len, avail);
      System.arraycopy(b, off, buf, count, prefixLength);
      count += prefixLength;
      off += prefixLength;
      len -= prefixLength;
      if (count == buf.length) {
        flushBuffer();
      }
    }
    // Write out the rest, chunk the writes, so we do not exceed the maximum encrypted message size
    while (len >= buf.length) {
      writeWrapped(b, off, buf.length);
      off += buf.length;
      len -= buf.length;
    }
    if (len == 0) {
      return;
    }
    System.arraycopy(b, off, buf, 0, len);
    count += len;
  }

}
