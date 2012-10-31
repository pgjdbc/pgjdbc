/*-------------------------------------------------------------------------
*
* Copyright (c) 2012, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.util;

/**
 * Simple container for host and port.
 */
public class HostSpec {
    protected final String host;
    protected final int port;

    public HostSpec(String host, int port)
    {
        this.host = host;
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String toString() {
        return host + ":" + port;
    }
}
