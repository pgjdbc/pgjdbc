/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.spi.InitialContextFactory;

/**
 * The ICF for a trivial JNDI implementation. This is not meant to be very useful, beyond testing
 * JNDI features of the connection pools.
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */
public class MiniJndiContextFactory implements InitialContextFactory {
  public Context getInitialContext(Hashtable<?, ?> environment) throws NamingException {
    return new MiniJndiContext();
  }
}
