/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/util/BrokenInputStream.java,v 1.3 2004/11/09 08:56:57 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.util;

import java.io.InputStream;
import java.io.IOException;

public class BrokenInputStream extends InputStream {

    private InputStream _is;
    private long _numRead;
    private long _breakOn;

    public BrokenInputStream(InputStream is, long breakOn) {
        _is = is;
        _breakOn = breakOn;
        _numRead = 0;
    }

    public int read() throws IOException {
        if (_breakOn > _numRead++)
        {
            throw new IOException("I was told to break on " + _breakOn);
        }

        return _is.read();
    }
}
