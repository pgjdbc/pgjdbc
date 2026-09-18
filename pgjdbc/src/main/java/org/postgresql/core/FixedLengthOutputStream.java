/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.io.IOException;
import java.io.OutputStream;

/**
 * A stream that refuses to write more than a maximum number of bytes.
 */
public class FixedLengthOutputStream extends OutputStream {

  private final int size;
  private final OutputStream target;
  private int written;

  public FixedLengthOutputStream(int size, OutputStream target) {
    this.size = size;
    this.target = target;
  }

  @Override
  public void write(int b) throws IOException {
    verifyAllowed(1);
    written++;
    target.write(b);
  }

  @Override
  public void write(byte[] buf, int offset, int len) throws IOException {
    // Written as a subtraction so that neither a len nor an offset near Integer.MAX_VALUE can wrap
    // the sum past the check. The target this forwards to does not necessarily bound its own copy,
    // so a range that gets past here is not caught anywhere
    if ((offset < 0) || (len < 0) || (len > buf.length - offset)) {
      throw new IndexOutOfBoundsException(
          "Range [" + offset + ", " + offset + " + " + len + ") out of bounds for length "
              + buf.length);
    } else if (len == 0) {
      return;
    }
    verifyAllowed(len);
    target.write(buf, offset, len);
    written += len;
  }

  public int remaining() {
    return size - written;
  }

  private void verifyAllowed(int wanted) throws IOException {
    if (remaining() < wanted) {
      throw new IOException("Attempt to write more than the specified " + size + " bytes");
    }
  }
}
