/*-------------------------------------------------------------------------
*
* Copyright (c) 2004, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/jdbc2/ANTTest.java,v 1.5 2004/11/07 22:16:40 jurka Exp $
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
