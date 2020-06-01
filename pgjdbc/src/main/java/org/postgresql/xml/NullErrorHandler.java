/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.xml;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/**
 * Error handler that silently suppresses all errors.
 */
public class NullErrorHandler implements ErrorHandler {
  public static final NullErrorHandler INSTANCE = new NullErrorHandler();

  public void error(SAXParseException e) {
  }

  public void fatalError(SAXParseException e) {
  }

  public void warning(SAXParseException e) {
  }
}
