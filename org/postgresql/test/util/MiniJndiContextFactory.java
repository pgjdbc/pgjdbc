/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/util/MiniJndiContextFactory.java,v 1.5 2004/11/09 08:57:03 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.util;

import java.util.*;
import javax.naming.*;
import javax.naming.spi.InitialContextFactory;

/**
 * The ICF for a trivial JNDI implementation.  This is not meant to
 * be very useful, beyond testing JNDI features of the connection
 * pools.
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */
public class MiniJndiContextFactory implements InitialContextFactory
{
    public Context getInitialContext(Hashtable environment)
    throws NamingException
    {
        return new MiniJndiContext();
    }
}
