/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/jdbc2/optional/SimpleDataSourceTest.java,v 1.7 2004/11/09 08:55:51 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2.optional;

import org.postgresql.test.TestUtil;
import org.postgresql.jdbc2.optional.SimpleDataSource;

/**
 * Performs the basic tests defined in the superclass. Just adds the
 * configuration logic.
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */
public class SimpleDataSourceTest extends BaseDataSourceTest
{
    /**
     * Constructor required by JUnit
     */
    public SimpleDataSourceTest(String name)
    {
        super(name);
    }

    /**
     * Creates and configures a new SimpleDataSource.
     */
    protected void initializeDataSource()
    {
        if (bds == null)
        {
            bds = new SimpleDataSource();
            bds.setServerName(TestUtil.getServer());
            bds.setPortNumber(TestUtil.getPort());
            bds.setDatabaseName(TestUtil.getDatabase());
            bds.setUser(TestUtil.getUser());
            bds.setPassword(TestUtil.getPassword());
            bds.setPrepareThreshold(TestUtil.getPrepareThreshold());
        }
    }
}
