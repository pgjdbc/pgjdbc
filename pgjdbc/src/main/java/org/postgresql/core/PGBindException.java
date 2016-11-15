/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.io.IOException;

public class PGBindException extends IOException {

  private IOException _ioe;

  public PGBindException(IOException ioe) {
    _ioe = ioe;
  }

  public IOException getIOException() {
    return _ioe;
  }
}
