/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.io.IOException;

/**
 * An integer socket option the socket it is bound to accepts.
 */
interface ExtendedSocketIntOption {

  /**
   * @param value the value to set
   * @throws IOException if the operating system rejects the value
   * @throws UnsupportedOperationException if the socket does not support the option after all
   */
  void setValue(int value) throws IOException;

  /**
   * @return the value the socket uses
   * @throws IOException if the operating system fails to report the value
   */
  int getValue() throws IOException;
}
