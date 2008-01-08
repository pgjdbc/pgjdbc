/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2008, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/PGBindException.java,v 1.4 2005/01/11 08:25:43 jurka Exp $
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
