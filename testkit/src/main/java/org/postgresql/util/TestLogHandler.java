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
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Pattern;

public class TestLogHandler extends Handler {
  public Queue<LogRecord> records = new ConcurrentLinkedQueue<>();

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
    List<LogRecord> matches = new ArrayList<>();
    for (LogRecord r: this.records) {
      String message = r.getMessage();
      if (message != null && messagePattern.matcher(message).find()) {
        matches.add(r);
      }
    }
    return matches;
  }

  /**
   * Runs the action with the logger of the given class at FINE and returns the messages it logged,
   * formatted with their parameters. While the action runs, the records go to the returned list
   * only, not to the parent handlers. Mark the test class {@code @Isolated}, or concurrent tests may
   * add records to the list and lose their own.
   */
  public static List<String> recordFineMessages(Class<?> loggerClass, Runnable action) {
    Logger logger = Logger.getLogger(loggerClass.getName());
    TestLogHandler logHandler = new TestLogHandler();
    Level previousLevel = logger.getLevel();
    boolean previousUseParentHandlers = logger.getUseParentHandlers();
    logger.addHandler(logHandler);
    logger.setLevel(Level.FINE);
    // Route the records to our handler only, so they do not pollute standard output
    logger.setUseParentHandlers(false);
    try {
      action.run();
    } finally {
      logger.removeHandler(logHandler);
      logger.setLevel(previousLevel);
      logger.setUseParentHandlers(previousUseParentHandlers);
    }
    SimpleFormatter formatter = new SimpleFormatter();
    List<String> messages = new ArrayList<>();
    for (LogRecord record : logHandler.records) {
      messages.add(formatter.formatMessage(record));
    }
    return messages;
  }
}
