/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

/**
 * {@link InputStream} implementation that reads less data than is provided in the destination
 * array. This allows to stress test {@link org.postgresql.copy.CopyManager} or other consumers.
 */
public class StrangeInputStream extends FilterInputStream {
  private final Random rand = new Random(); // generator of fun events

  public StrangeInputStream(InputStream is, long seed) throws FileNotFoundException {
    super(is);
    rand.setSeed(seed);
  }

  @Override
  public int read(byte[] b) throws IOException {
    int maxRead = rand.nextInt(b.length);
    return super.read(b, 0, maxRead);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int maxRead = rand.nextInt(len);
    return super.read(b, off, maxRead);
  }
}
