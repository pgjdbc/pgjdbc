/*-------------------------------------------------------------------------
*
* Copyright (c) 2005-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc2;

import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;

import legacy.org.postgresql.core.BaseConnection;
import legacy.org.postgresql.util.GT;
import legacy.org.postgresql.util.PSQLException;
import legacy.org.postgresql.util.PSQLState;
import legacy.org.postgresql.largeobject.LargeObject;
import legacy.org.postgresql.largeobject.LargeObjectManager;

/**
 * This class holds all of the methods common to both Blobs and Clobs.
 *
 * @author Michael Barker <mailto:mike@middlesoft.co.uk>
 *
 */
public abstract class AbstractJdbc2BlobClob
{
    protected BaseConnection conn;
    protected LargeObject lo;

    /**
     * We create separate LargeObjects for methods that use streams
     * so they won't interfere with each other.
     */
    private ArrayList subLOs;

    public AbstractJdbc2BlobClob(BaseConnection conn, long oid) throws SQLException
    {
        this.conn = conn;
        LargeObjectManager lom = conn.getLargeObjectAPI();
        this.lo = lom.open(oid);
        subLOs = new ArrayList();
    }

    public synchronized void free() throws SQLException
    {
        if (lo != null) {
            lo.close();
            lo = null;
        }
        Iterator i = subLOs.iterator();
        while (i.hasNext()) {
            LargeObject subLO = (LargeObject)i.next();
            subLO.close();
        }
        subLOs = null;
    }

    /**
     * For Blobs this should be in bytes while for Clobs it should be
     * in characters.  Since we really haven't figured out how to handle
     * character sets for Clobs the current implementation uses bytes for
     * both Blobs and Clobs.
     */
    public synchronized void truncate(long len) throws SQLException
    {
        checkFreed();
        if (!conn.haveMinimumServerVersion("8.3"))
            throw new PSQLException(GT.tr("Truncation of large objects is only implemented in 8.3 and later servers."), PSQLState.NOT_IMPLEMENTED);

        if (len < 0)
        {
            throw new PSQLException(GT.tr("Cannot truncate LOB to a negative length."), PSQLState.INVALID_PARAMETER_VALUE);
        }
        if (len > Integer.MAX_VALUE)
        {
            throw new PSQLException(GT.tr("PostgreSQL LOBs can only index to: {0}", new Integer(Integer.MAX_VALUE)), PSQLState.INVALID_PARAMETER_VALUE);
        }

        lo.truncate((int)len);
    }

    public synchronized long length() throws SQLException
    {
        checkFreed();
        return lo.size();
    }

    public synchronized byte[] getBytes(long pos, int length) throws SQLException
    {
        assertPosition(pos);
        lo.seek((int)(pos-1), LargeObject.SEEK_SET);
        return lo.read(length);
    }


    public synchronized InputStream getBinaryStream() throws SQLException
    {
        checkFreed();
        LargeObject subLO = lo.copy();
        subLOs.add(subLO);
        subLO.seek(0, LargeObject.SEEK_SET);
        return subLO.getInputStream();
    }

    public synchronized OutputStream setBinaryStream(long pos) throws SQLException
    {
        assertPosition(pos);
        LargeObject subLO = lo.copy();
        subLOs.add(subLO);
        subLO.seek((int)(pos-1));
        return subLO.getOutputStream();
    }

    /**
     * Iterate over the buffer looking for the specified pattern
     * 
     * @param pattern A pattern of bytes to search the blob for.
     * @param start The position to start reading from.
     */
    public synchronized long position(byte[] pattern, long start) throws SQLException
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
    public synchronized long position(Blob pattern, long start) throws SQLException
    {
        return position(pattern.getBytes(1, (int)pattern.length()), start);
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
        checkFreed();
        if (pos < 1)
        {
            throw new PSQLException(GT.tr("LOB positioning offsets start at 1."), PSQLState.INVALID_PARAMETER_VALUE);
        }
        if (pos + len - 1 > Integer.MAX_VALUE)
        {
            throw new PSQLException(GT.tr("PostgreSQL LOBs can only index to: {0}", new Integer(Integer.MAX_VALUE)), PSQLState.INVALID_PARAMETER_VALUE);
        }
    }

    /**
     * Checks that this LOB hasn't been free()d already.
     * @throws SQLException if LOB has been freed.
     */
    protected void checkFreed() throws SQLException
    {
        if (lo == null)
            throw new PSQLException(GT.tr("free() was called on this LOB previously"), PSQLState.OBJECT_NOT_IN_STATE);
    }

}
