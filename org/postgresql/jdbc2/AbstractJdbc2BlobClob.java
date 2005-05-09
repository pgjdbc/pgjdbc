/*-------------------------------------------------------------------------
*
* Copyright (c) 2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/AbstractJdbc2BlobClob.java,v 1.2 2005/05/08 23:18:24 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;

import java.io.InputStream;
import java.sql.Blob;
import java.sql.SQLException;

import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLState;
import org.postgresql.util.PSQLException;

/**
 * This class holds all of the methods common to both Blobs and Clobs.
 *
 * At the moment only Blob uses this, but the intention is that Clob
 * eventually will once we figure out what to do with encodings.
 * 
 * @author Michael Barker <mailto:mike@middlesoft.co.uk>
 *
 */
public class AbstractJdbc2BlobClob
{
    private LargeObject lo;

    public AbstractJdbc2BlobClob(PGConnection conn, int oid) throws SQLException
    {
        LargeObjectManager lom = conn.getLargeObjectAPI();
        this.lo = lom.open(oid);
    }

    public long length() throws SQLException
    {
        return lo.size();
    }

    public byte[] getBytes(long pos, int length) throws SQLException
    {
        assertPosition(pos);
        lo.seek((int)(pos-1), LargeObject.SEEK_SET);
        return lo.read(length);
    }


    public InputStream getBinaryStream() throws SQLException
    {
        return lo.getInputStream();
    }


    /**
     * Iterate over the buffer looking for the specified pattern
     * 
     * @param pattern A pattern of bytes to search the blob for.
     * @param start The position to start reading from.
     */
    public long position(byte[] pattern, long start) throws SQLException
    {
        assertPosition(start, pattern.length);

        int position = 1;
        int patternIdx = 0;
        long result = -1;
        int tmpPosition = 1;

        for (LOIterator i = new LOIterator(start-1); i.hasNext(); position++)
        {
            byte b = i.next();
            if (b == pattern[patternIdx])
            {
                if (patternIdx == 0)
                {
                    tmpPosition = position;                    
                }
                patternIdx++;
                if (patternIdx == pattern.length)
                {
                    result = tmpPosition;
                    break;
                }
            }
            else
            {
                patternIdx = 0;
            }
        }

        return result;
    }

    /**
     * Iterates over a large object returning byte values.  Will buffer
     * the data from the large object.
     * 
     *
     */
    private class LOIterator
    {
        private static final int BUFFER_SIZE = 8096;
        private byte buffer[] = new byte[BUFFER_SIZE];
        private int idx = BUFFER_SIZE;
        private int numBytes = BUFFER_SIZE;

        public LOIterator(long start) throws SQLException
        {
            lo.seek((int) start);
        }

        public boolean hasNext() throws SQLException
        {
            boolean result = false;
            if (idx < numBytes)
            {
                result = true;
            }
            else
            {
                numBytes = lo.read(buffer, 0, BUFFER_SIZE);
                idx = 0;
                result = (numBytes > 0);
            }
            return result;
        }

        private byte next()
        {
            return buffer[idx++];
        }
    }


    /**
     * This is simply passing the byte value of the pattern Blob
     */
    public long position(Blob pattern, long start) throws SQLException
    {
        return position(pattern.getBytes(1, (int)pattern.length()), start);
    }

    /**
     * Expose large object to derived classes.
     */
    protected LargeObject getLO()
    {
       return lo;
    }

    /**
     * Throws an exception if the pos value exceeds the max value by
     * which the large object API can index.
     * 
     * @param pos Position to write at.
     */
    protected void assertPosition(long pos) throws SQLException
    {
        assertPosition(pos, 0);
    }

    /**
     * Throws an exception if the pos value exceeds the max value by
     * which the large object API can index.
     * 
     * @param pos Position to write at.
     * @param len number of bytes to write.
     */
    protected void assertPosition(long pos, long len) throws SQLException
    {
        if (pos < 1)
        {
            throw new PSQLException(GT.tr("LOB positioning offsets start at 1."), PSQLState.INVALID_PARAMETER_VALUE);
        }
        if (pos + len - 1 > Integer.MAX_VALUE)
        {
            throw new PSQLException(GT.tr("PostgreSQL LOBs can only index to: {0}", new Integer(Integer.MAX_VALUE)), PSQLState.INVALID_PARAMETER_VALUE);
        }
    }

}
