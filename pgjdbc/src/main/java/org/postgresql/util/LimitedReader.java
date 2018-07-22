/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

public class LimitedReader extends BufferedReader {
  private final long maxLength;
  private long position;
  private long markPosition;

  public LimitedReader(Reader in, int sz, long maxLength) {
    super(in, sz);
    this.maxLength = maxLength;
  }

  @Override
  public int read() throws IOException {
    if (position == maxLength) {
      return -1;
    }
    int read = super.read();
    position++;
    return read;
  }

  @Override
  public int read(char[] cbuf, int off, int len) throws IOException {
    if (position == maxLength) {
      return -1;
    }
    if (position + len > maxLength) {
      len = (int) (maxLength - position);
    }
    int read = super.read(cbuf, off, len);
    position += read;
    return read;
  }

  @Override
  public void mark(int readAheadLimit) throws IOException {
    super.mark(readAheadLimit);
    markPosition = position;
  }

  @Override
  public void reset() throws IOException {
    position = markPosition;
    super.reset();
  }

  @Override
  public long skip(long n) throws IOException {
    if (n == 0) {
      return 0;
    }
    if (position + n > maxLength) {
      n = (int) (maxLength - position);
    }
    long skip = super.skip(n);
    position += skip;
    return skip;
  }

  @Override
  public String readLine() {
    throw new UnsupportedOperationException("LimitedReader.readLine");
  }
}
