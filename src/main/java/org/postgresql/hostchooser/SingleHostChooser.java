/*-------------------------------------------------------------------------
*
* Copyright (c) 2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.hostchooser;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

import org.postgresql.util.HostSpec;

/**
 * Host chooser that returns the single host.
 */
public class SingleHostChooser implements HostChooser {
    private final Collection<HostSpec> hostSpec;

    public SingleHostChooser(HostSpec hostSpec) {
        this.hostSpec = Collections.singletonList(hostSpec);
    }

    public Iterator<HostSpec> iterator() {
        return hostSpec.iterator();
    }
}
