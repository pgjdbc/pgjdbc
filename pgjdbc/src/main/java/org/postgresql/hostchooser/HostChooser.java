/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2014, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.hostchooser;

import java.util.Iterator;

import org.postgresql.util.HostSpec;

/**
 * Lists connections in preferred order.
 */
public interface HostChooser {
    /**
     * Lists connection hosts in preferred order.
     *
     * @return connection hosts in preferred order.
     */
    Iterator<HostSpec> iterator();
}
