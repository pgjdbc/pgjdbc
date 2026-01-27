/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;

/**
 * {@link OutputStream} implementation that breaks writes into several individual writes. It
 * allows to stress test other {@link OutputStream} implementations.
 * For instance, it allows to test non-zero offset writes from the source buffers,
 * and it might convert buffered writes into individual byte-by-byte writes.
 */
public class StrangeOutputStream extends FilterOutputStream {
  private final Random rand = new Random(); // generator of fun events
  private final byte[] oneByte = new byte[1];
  private final double flushProbability;

  public StrangeOutputStream(OutputStream os, long seed, double flushProbability) {
    super(os);
    this.flushProbability = flushProbability;
    rand.setSeed(seed);
  }

  @Override
  public void write(int b) throws IOException {
    oneByte[0] = (byte) b;
    out.write(oneByte);
    if (rand.nextDouble() < flushProbability) {
      flush();
    }
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    while (len > 0) {
      int maxWrite = rand.nextInt(len + 1);
      if (maxWrite == 1 && rand.nextBoolean()) {
        out.write(b[off]);
      } else {
        out.write(b, off, maxWrite);
      }
      off += maxWrite;
      len -= maxWrite;
      if (rand.nextDouble() < flushProbability) {
        flush();
      }
    }
  }
}
