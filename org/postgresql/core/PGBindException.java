/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/PGBindException.java,v 1.5 2008/01/08 06:56:27 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

import java.io.IOException;

public class PGBindException extends IOException {

    private IOException _ioe;

    public PGBindException(IOException ioe) {
        _ioe = ioe;
    }

    public IOException getIOException() {
        return _ioe;
    }
}
