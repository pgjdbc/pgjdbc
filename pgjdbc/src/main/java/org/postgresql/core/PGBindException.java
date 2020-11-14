/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.io.IOException;

public class PGBindException extends IOException {

  private final IOException ioe;

  public PGBindException(IOException ioe) {
    this.ioe = ioe;
  }

  public IOException getIOException() {
    return ioe;
  }
}
