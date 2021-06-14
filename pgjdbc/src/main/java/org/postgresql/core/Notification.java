/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.PGNotification;

public class Notification implements PGNotification {

  private final String name;
  private final String parameter;
  private final int pid;

  public Notification(String name, int pid) {
    this(name, pid, "");
  }

  public Notification(String name, int pid, String parameter) {
    this.name = name;
    this.pid = pid;
    this.parameter = parameter;
  }

  /*
   * Returns name of this notification
   */
  public String getName() {
    return name;
  }

  /*
   * Returns the process id of the backend process making this notification
   */
  public int getPID() {
    return pid;
  }

  public String getParameter() {
    return parameter;
  }

}
