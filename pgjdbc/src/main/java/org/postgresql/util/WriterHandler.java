/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.postgresql.util;

import java.io.Writer;

import java.util.logging.ErrorManager;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

public class WriterHandler extends Handler {

  private boolean doneHeader;

  private Writer writer;

  public WriterHandler(Writer inWriter) {
    super();
    setLevel(Level.INFO);
    setFilter(null);
    setFormatter(new SimpleFormatter());
    writer = inWriter;
  }

  public WriterHandler(Writer inWriter, Formatter formatter) {
    super();
    setLevel(Level.INFO);
    setFilter(null);
    setFormatter(formatter);
    writer = inWriter;

  }

  public synchronized void publish(final LogRecord record) {
    if (!isLoggable(record)) {
      return;
    }
    String msg;
    try {
      msg = getFormatter().format(record);
    } catch (Exception ex) {
      // We don't want to throw an exception here, but we
      // report the exception to any registered ErrorManager.
      reportError(null, ex, ErrorManager.FORMAT_FAILURE);
      return;
    }

    try {
      if (!doneHeader) {
        writer.write(getFormatter().getHead(this));
        doneHeader = true;
      }
      writer.write(msg);
    } catch (Exception ex) {
      // We don't want to throw an exception here, but we
      // report the exception to any registered ErrorManager.
      reportError(null, ex, ErrorManager.WRITE_FAILURE);
    }
  }

  public boolean isLoggable(final LogRecord record) {
    if (writer == null || record == null) {
      return false;
    }
    return super.isLoggable(record);
  }

  public synchronized void flush() {
    if (writer != null) {
      try {
        writer.flush();
      } catch (Exception ex) {
        // We don't want to throw an exception here, but we
        // report the exception to any registered ErrorManager.
        reportError(null, ex, ErrorManager.FLUSH_FAILURE);
      }
    }
  }

  private synchronized void flushAndClose() throws SecurityException {

    if (writer != null) {
      try {
        if (!doneHeader) {
          writer.write(getFormatter().getHead(this));
          doneHeader = true;
        }
        writer.write(getFormatter().getTail(this));
        writer.flush();
        writer.close();
      } catch (Exception ex) {
        // We don't want to throw an exception here, but we
        // report the exception to any registered ErrorManager.
        reportError(null, ex, ErrorManager.CLOSE_FAILURE);
      }
      writer = null;

    }
  }

  public synchronized void close() throws SecurityException {
    flushAndClose();
  }
}
