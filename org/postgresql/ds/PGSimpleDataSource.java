/*-------------------------------------------------------------------------
*
* Copyright (c) 2004, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/ds/PGSimpleDataSource.java,v 1.2 2004/11/07 22:15:42 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.ds;

import javax.sql.DataSource;
import java.io.Serializable;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.io.IOException;

import org.postgresql.ds.common.*;

/**
 * Simple DataSource which does not perform connection pooling.  In order to use
 * the DataSource, you must set the property databaseName. The settings for
 * serverName, portNumber, user, and password are optional.  Note: these properties
 * are declared in the superclass.
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */
public class PGSimpleDataSource extends BaseDataSource implements Serializable, DataSource
{
    /**
     * Gets a description of this DataSource.
     */
    public String getDescription()
    {
        return "Non-Pooling DataSource from " + org.postgresql.Driver.getVersion();
    }

    private void writeObject(ObjectOutputStream out) throws IOException
    {
        writeBaseObject(out);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException
    {
        readBaseObject(in);
    }
}
