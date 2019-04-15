/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import java.io.IOException;
import java.io.InputStream;

public class BrokenInputStream extends InputStream {

  private final InputStream is;
  private final long breakOn;
  private long numRead;

  public BrokenInputStream(InputStream is, long breakOn) {
    this.is = is;
    this.breakOn = breakOn;
    this.numRead = 0;
  }

  @Override
  public int read() throws IOException {
    if (breakOn > numRead++) {
      throw new IOException("I was told to break on " + breakOn);
    }

    return is.read();
  }
}
