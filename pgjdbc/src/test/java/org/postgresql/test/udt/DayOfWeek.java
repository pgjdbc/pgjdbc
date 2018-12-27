/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;


public enum DayOfWeek {
  sunday("Sunday"),
  monday("Monday"),
  tuesday("Tuesday"),
  wednesday("Wednesday"),
  thursday("Thursday"),
  friday("Friday"),
  saturday("Saturday");

  private final String toString;

  DayOfWeek(String toString) {
    this.toString = toString;
  }

  /**
   * Intentionally does not match the value of {@link #name()}, to be able to test
   * that {@link #name()} is being used instead of {@link #toString()} when {@link Enum}
   * support is enabled and active.
   */
  @Override
  public String toString() {
    return toString;
  }
}
