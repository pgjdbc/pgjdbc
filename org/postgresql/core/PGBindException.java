/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/PGBindException.java,v 1.3 2004/11/09 08:44:35 jurka Exp $
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
