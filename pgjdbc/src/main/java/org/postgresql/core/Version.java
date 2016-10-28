/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

public interface Version {

  /**
   * Get a machine-readable version number.
   *
   * @return the version in numeric XXYYZZ form, e.g. 90401 for 9.4.1
   */
  int getVersionNum();

}
