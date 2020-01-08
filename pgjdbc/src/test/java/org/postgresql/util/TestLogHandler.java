package org.postgresql.util;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

public class TestLogHandler extends Handler {
  public List<LogRecord> records = new ArrayList<>();

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
    ArrayList<LogRecord> matches = new ArrayList<>();
    for (LogRecord r: this.records) {
      if (messagePattern.matcher(r.getMessage()).find()) {
        matches.add(r);
      }
    }
    return matches;
  }
}
