/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.composite;

public final class ServicePoint {
  final double x;
  final double y;

  public ServicePoint(double x, double y) {
    this.x = x;
    this.y = y;
  }
}
