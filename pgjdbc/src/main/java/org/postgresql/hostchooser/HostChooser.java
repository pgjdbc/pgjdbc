/*
 * Copyright (c) 2014, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

import org.postgresql.util.HostSpec;

import java.util.Iterator;

/**
 * Lists connections in preferred order.
 */
public interface HostChooser extends Iterable<HostSpec> {
  /**
   * Lists connection hosts in preferred order.
   *
   * @return connection hosts in preferred order.
   */
  @Override
  Iterator<HostSpec> iterator();
}
