/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.largeobject;


import java.sql.ResultSet;
import java.sql.SQLException;
import org.postgresql.core.BaseConnection;
import org.postgresql.fastpath.Fastpath;
import org.postgresql.fastpath.FastpathArg;
import org.postgresql.util.PSQLException;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLState;

/**
 * This class implements the large object interface to org.postgresql.
 *
 * <p>It provides methods that allow client code to create, open and delete
 * large objects from the database. When opening an object, an instance of
 * org.postgresql.largeobject.LargeObject is returned, and its methods then allow
 * access to the object.
 *
 * <p>This class can only be created by org.postgresql.Connection
 *
 * <p>To get access to this class, use the following segment of code:
 * <br><pre>
 * import org.postgresql.largeobject.*;
 *
 * Connection  conn;
 * LargeObjectManager lobj;
 *
 * ... code that opens a connection ...
 *
 * lobj = ((org.postgresql.PGConnection)myconn).getLargeObjectAPI();
 * </pre>
 *
 * <p>Normally, client code would use the getAsciiStream, getBinaryStream,
 * or getUnicodeStream methods in ResultSet, or setAsciiStream,
 * setBinaryStream, or setUnicodeStream methods in PreparedStatement to
 * access Large Objects.
 *
 * <p>However, sometimes lower level access to Large Objects are required,
 * that are not supported by the JDBC specification.
 *
 * <p>Refer to org.postgresql.largeobject.LargeObject on how to manipulate the
 * contents of a Large Object.
 *
 * @see java.sql.ResultSet#getAsciiStream
 * @see java.sql.ResultSet#getBinaryStream
 * @see java.sql.ResultSet#getUnicodeStream
 * @see java.sql.PreparedStatement#setAsciiStream
 * @see java.sql.PreparedStatement#setBinaryStream
 * @see java.sql.PreparedStatement#setUnicodeStream
 */
public class LargeObjectManager
{
    // the fastpath api for this connection
    private Fastpath fp;
    private BaseConnection conn;

    /**
     * This mode indicates we want to write to an object
     */
    public static final int WRITE = 0x00020000;

    /**
     * This mode indicates we want to read an object
     */
    public static final int READ = 0x00040000;

    /**
     * This mode is the default. It indicates we want read and write access to
     * a large object
     */
    public static final int READWRITE = READ | WRITE;

    /**
     * This prevents us being created by mere mortals
     */
    private LargeObjectManager()
    {
    }

    /**
     * Constructs the LargeObject API.
     *
     * <p><b>Important Notice</b>
     * <br>This method should only be called by org.postgresql.Connection
     *
     * <p>There should only be one LargeObjectManager per Connection. The
     * org.postgresql.Connection class keeps track of the various extension API's
     * and it's advised you use those to gain access, and not going direct.
     */
    public LargeObjectManager(BaseConnection conn) throws SQLException
    {
        this.conn = conn;
        // We need Fastpath to do anything
        this.fp = conn.getFastpathAPI();

        // Now get the function oid's for the api
        //
        // This is an example of Fastpath.addFunctions();
        //
        String sql;
        if (conn.getMetaData().supportsSchemasInTableDefinitions())
        {
            sql = "SELECT p.proname,p.oid " +
                  " FROM pg_catalog.pg_proc p, pg_catalog.pg_namespace n " +
                  " WHERE p.pronamespace=n.oid AND n.nspname='pg_catalog' AND (";
        }
        else
        {
            sql = "SELECT proname,oid FROM pg_proc WHERE ";
        }
        sql += " proname = 'lo_open'" +
               " or proname = 'lo_close'" +
               " or proname = 'lo_creat'" +
               " or proname = 'lo_unlink'" +
               " or proname = 'lo_lseek'" +
               " or proname = 'lo_tell'" +
               " or proname = 'loread'" +
               " or proname = 'lowrite'" +
               " or proname = 'lo_truncate'";

        if (conn.getMetaData().supportsSchemasInTableDefinitions())
        {
            sql += ")";
        }

        ResultSet res = conn.createStatement().executeQuery(sql);

        if (res == null)
            throw new PSQLException(GT.tr("Failed to initialize LargeObject API"), PSQLState.SYSTEM_ERROR);

        fp.addFunctions(res);
        res.close();

        conn.getLogger().debug("Large Object initialised");
    }

    /**
     * This opens an existing large object, based on its OID. This method
     * assumes that READ and WRITE access is required (the default).
     *
     * @param oid of large object
     * @return LargeObject instance providing access to the object
     * @exception SQLException on error
     * @deprecated As of 8.3, replaced by {@link #open(long)}
     */
    public LargeObject open(int oid) throws SQLException
    {
        return open((long)oid);
    }

    /**
     * This opens an existing large object, based on its OID. This method
     * assumes that READ and WRITE access is required (the default).
     *
     * @param oid of large object
     * @return LargeObject instance providing access to the object
     * @exception SQLException on error
     */
    public LargeObject open(long oid) throws SQLException
    {
        return open(oid, READWRITE);
    }

    /**
     * This opens an existing large object, based on its OID
     *
     * @param oid of large object
     * @param mode mode of open
     * @return LargeObject instance providing access to the object
     * @exception SQLException on error
     * @deprecated As of 8.3, replaced by {@link #open(long, int)}
     */
    public LargeObject open(int oid, int mode) throws SQLException
    {
        return open((long)oid, mode);
    }

    /**
     * This opens an existing large object, based on its OID
     *
     * @param oid of large object
     * @param mode mode of open
     * @return LargeObject instance providing access to the object
     * @exception SQLException on error
     */
    public LargeObject open(long oid, int mode) throws SQLException
    {
        if (conn.getAutoCommit())
            throw new PSQLException(GT.tr("Large Objects may not be used in auto-commit mode."),
                                    PSQLState.NO_ACTIVE_SQL_TRANSACTION);
        return new LargeObject(fp, oid, mode);
    }

    /**
     * This creates a large object, returning its OID.
     *
     * <p>It defaults to READWRITE for the new object's attributes.
     *
     * @return oid of new object
     * @exception SQLException on error
     * @deprecated As of 8.3, replaced by {@link #createLO()}
     */
    public int create() throws SQLException
    {
        return create(READWRITE);
    }

    /**
     * This creates a large object, returning its OID.
     *
     * <p>It defaults to READWRITE for the new object's attributes.
     *
     * @return oid of new object
     */
    public long createLO() throws SQLException
    {
        return createLO(READWRITE);
    }

    /**
     * This creates a large object, returning its OID
     *
     * @param mode a bitmask describing different attributes of the new object
     * @return oid of new object
     * @exception SQLException on error
     */
    public long createLO(int mode) throws SQLException
    {
        if (conn.getAutoCommit())
            throw new PSQLException(GT.tr("Large Objects may not be used in auto-commit mode."),
                                    PSQLState.NO_ACTIVE_SQL_TRANSACTION);
        FastpathArg args[] = new FastpathArg[1];
        args[0] = new FastpathArg(mode);
        return fp.getOID("lo_creat", args);
    }

    /**
     * This creates a large object, returning its OID
     *
     * @param mode a bitmask describing different attributes of the new object
     * @return oid of new object
     * @exception SQLException on error
     * @deprecated As of 8.3, replaced by {@link #createLO(int)}
     */
    public int create(int mode) throws SQLException
    {
        long oid = createLO(mode);
        return (int)oid;
    }

    /**
     * This deletes a large object.
     *
     * @param oid describing object to delete
     * @exception SQLException on error
     */
    public void delete(long oid) throws SQLException
    {
        FastpathArg args[] = new FastpathArg[1];
        args[0] = Fastpath.createOIDArg(oid);
        fp.fastpath("lo_unlink", false, args);
    }

    /**
     * This deletes a large object.
     *
     * <p>It is identical to the delete method, and is supplied as the C API uses
     * unlink.
     *
     * @param oid describing object to delete
     * @exception SQLException on error
     * @deprecated As of 8.3, replaced by {@link #unlink(long)}
     */
    public void unlink(int oid) throws SQLException
    {
        delete((long)oid);
    }

    /**
     * This deletes a large object.
     *
     * <p>It is identical to the delete method, and is supplied as the C API uses
     * unlink.
     *
     * @param oid describing object to delete
     * @exception SQLException on error
     */
    public void unlink(long oid) throws SQLException
    {
        delete(oid);
    }

    /**
     * This deletes a large object.
     *
     * @param oid describing object to delete
     * @exception SQLException on error
     * @deprecated As of 8.3, replaced by {@link #delete(long)}
     */
    public void delete(int oid) throws SQLException
    {
        delete((long)oid);
    }

}
