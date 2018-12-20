/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

/**
 * A user-defined data type implemented as a domain over integer on the
 * server-side.
 */
public interface Port extends Comparable<Port> {

  int getPort();
}
