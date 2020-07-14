/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.MessageProp;

import java.io.IOException;
import java.io.OutputStream;

public class GSSOutputStream extends OutputStream {
  private final GSSContext gssContext;
  private final MessageProp messageProp;
  private byte[] buffer;
  private byte[] int4Buf = new byte[4];
  private int index;
  private OutputStream wrapped;

  public GSSOutputStream(OutputStream out, GSSContext gssContext, MessageProp messageProp, int bufferSize)  {
    wrapped = out;
    this.gssContext = gssContext;
    this.messageProp = messageProp;
    buffer = new byte[bufferSize];
  }

  @Override
  public void write(int b) throws IOException {
    buffer[index++] = (byte)b;
    if (index >= buffer.length) {
      flush();
    }
  }

  @Override
  public void write(byte[] buf) throws IOException {
    write(buf, 0, buf.length);
  }

  @Override
  public void write(byte[] b, int pos, int len) throws IOException {
    int max;

    while ( len > 0 ) {
      int roomToWrite = buffer.length - index;
      if ( len < roomToWrite ) {
        System.arraycopy(b, pos,buffer, index, len);
        index += len;
        len -= roomToWrite;
      } else {
        System.arraycopy(b, pos, buffer, index, roomToWrite );
        index += roomToWrite;
        len -= roomToWrite;
      }
      if (roomToWrite == 0) {
        flush();
      }
    }
  }

  @Override
  public void flush() throws IOException {
    try {
      byte[] token = gssContext.wrap(buffer, 0, index, messageProp);
      sendInteger4Raw(token.length);
      wrapped.write(token, 0, token.length);
      index = 0;
    } catch ( GSSException ex ) {
      throw new IOException(ex);
    }
    wrapped.flush();
  }

  private void sendInteger4Raw(int val) throws IOException {
    int4Buf[0] = (byte) (val >>> 24);
    int4Buf[1] = (byte) (val >>> 16);
    int4Buf[2] = (byte) (val >>> 8);
    int4Buf[3] = (byte) (val);
    wrapped.write(int4Buf);
  }

}
