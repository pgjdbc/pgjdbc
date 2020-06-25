/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.io.Writer;
import java.util.logging.ErrorManager;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

public class LogWriterHandler extends Handler {

  Writer writer;
  private  final Object lock = new Object();

  public LogWriterHandler(Writer inWriter) {
    super();
    setLevel(Level.INFO);
    setFilter(null);
    setFormatter(new SimpleFormatter());
    setWriter(inWriter);
  }

  @Override
  public void publish(LogRecord record) {
    final String formatted;
    final Formatter formatter = getFormatter();

    try {
      formatted = formatter.format(record);
    } catch (Exception ex) {
      reportError("Error Formatting record", ex, ErrorManager.FORMAT_FAILURE);
      return;
    }

    if (formatted.length() == 0) {
      return;
    }
    try {
      synchronized (lock) {
        if (writer != null) {
          writer.write(formatted);
        }
      }
    } catch (Exception ex) {
      reportError("Error writing message", ex, ErrorManager.WRITE_FAILURE);
      return;
    }
  }

  @Override
  public void flush() {
    try {
      if ( writer != null ) {
        writer.flush();
      }
    } catch ( Exception ex ) {
      reportError("Error on flush", ex, ErrorManager.WRITE_FAILURE);
    }
  }

  @Override
  public void close() throws SecurityException {
    try {
      if ( writer != null ) {
        writer.close();
      }
    } catch ( Exception ex ) {
      reportError("Error closing writer", ex, ErrorManager.WRITE_FAILURE);
    }
  }

  private void setWriter(Writer writer) {
    this.writer = writer;

    try {
      if ( writer != null ) {
        writer.write(getFormatter().getHead(this));
      }
    } catch ( Exception ex) {
      reportError("Error writing head section", ex, ErrorManager.WRITE_FAILURE);
    }
  }
}
