/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

public class TestLogHandler extends Handler {
  public Queue<LogRecord> records = new ConcurrentLinkedQueue<LogRecord>();

  @Override
  public void publish(LogRecord record) {
    records.add(record);
  }

  @Override
  public void flush() {
  }

  @Override
  public void close() throws SecurityException {
  }

  public List<LogRecord> getRecordsMatching(Pattern messagePattern) {
    List<LogRecord> matches = new ArrayList<LogRecord>();
    for (LogRecord r: this.records) {
      String message = r.getMessage();
      if (message != null && messagePattern.matcher(message).find()) {
        matches.add(r);
      }
    }
    return matches;
  }
}
