/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

public class NewBufferedInputStream extends BufferedInputStream {
  public NewBufferedInputStream(InputStream in) {
    super(in);
  }

  public NewBufferedInputStream(InputStream in, int size) {
    super(in, size);
  }

  public int peek() throws SocketTimeoutException, IOException {

    if ( super.available() > 0 ) {
    try {
      mark(2);
      return read();
    } finally {
      reset();
    }
    } else {
      return -1;
    }
  }

  @Override
  public int read(byte[] to, int off, int len) throws IOException {
    int bytesRead = 0;
    int curOffset = 0;
    int bytesLeft = len;

    while (bytesRead < len) {
      bytesRead += super.read(to, curOffset, bytesLeft);
      curOffset += bytesRead;
      bytesLeft -= bytesRead;
    }
    return bytesRead;
  }
}
