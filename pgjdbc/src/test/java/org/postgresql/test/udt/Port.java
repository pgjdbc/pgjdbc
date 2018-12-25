/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

import java.sql.SQLData;

/**
 * A user-defined data type implemented as a domain over integer on the
 * server-side, demonstrating separation of interface from implementation.
 * <p>
 * This intentionally does not extend {@link SQLData}, where the implementing
 * class {@link PortImpl} does.
 * </p>
 */
public interface Port extends Comparable<Port> {

  int getPort();
}
