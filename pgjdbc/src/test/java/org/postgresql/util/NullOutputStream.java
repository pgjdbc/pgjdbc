/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Created by davec on 3/14/17.
 */
public class NullOutputStream extends PrintStream {
  private static class BlackHole extends OutputStream {
    @Override
    public void write(int b) throws IOException {
    }
  }

  public NullOutputStream() {
    super(new BlackHole());
  }

  public NullOutputStream(OutputStream out) {
    super(out);
  }

  @Override
  public void write(int b) {

  }

  @Override
  public void write(byte[] buf, int off, int len) {

  }
}
