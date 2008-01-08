/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2008, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/jdbc2/ANTTest.java,v 1.7 2005/01/11 08:25:48 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2;

import junit.framework.TestCase;

public class ANTTest extends TestCase
{
    public ANTTest(String name)
    {
        super(name);
    }

    /*
     * This tests the acceptsURL() method with a couple of good and badly formed
     * jdbc urls
     */
    public void testANT()
    {
        String url = System.getProperty("database");
        String usr = System.getProperty("username");
        String psw = System.getProperty("password");

        assertNotNull(url);
        assertNotNull(usr);
        assertNotNull(psw);

        assertTrue(! url.equals(""));
        assertTrue(! usr.equals(""));
    }
}
