/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.util;

import java.io.Serializable;
import java.rmi.MarshalledObject;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import javax.naming.Binding;
import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NameClassPair;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.Referenceable;
import javax.naming.spi.ObjectFactory;

/**
 * The Context for a trivial JNDI implementation.  This is not meant to be very useful, beyond
 * testing JNDI features of the connection pools.  It is not a complete JNDI implementations.
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */
public class MiniJndiContext implements Context {
  private Map<String, Object> map = new HashMap<String, Object>();

  public MiniJndiContext() {
  }

  public Object lookup(Name name) throws NamingException {
    return lookup(name.get(0));
  }

  public Object lookup(String name) throws NamingException {
    Object o = map.get(name);
    if (o == null) {
      return null;
    }
    if (o instanceof Reference) {
      Reference ref = (Reference) o;
      try {
        Class<?> factoryClass = Class.forName(ref.getFactoryClassName());
        ObjectFactory fac = (ObjectFactory) factoryClass.newInstance();
        Object result = fac.getObjectInstance(ref, null, this, null);
        return result;
      } catch (Exception e) {
        throw new NamingException("Unable to dereference to object: " + e);
      }
    } else if (o instanceof MarshalledObject) {
      try {
        Object result = ((MarshalledObject<?>) o).get();
        return result;
      } catch (java.io.IOException e) {
        throw new NamingException("Unable to deserialize object: " + e);
      } catch (ClassNotFoundException e) {
        throw new NamingException("Unable to deserialize object: " + e);
      }
    } else {
      throw new NamingException("JNDI Object is neither Referenceable nor Serializable");
    }
  }

  public void bind(Name name, Object obj) throws NamingException {
    rebind(name.get(0), obj);
  }

  public void bind(String name, Object obj) throws NamingException {
    rebind(name, obj);
  }

  public void rebind(Name name, Object obj) throws NamingException {
    rebind(name.get(0), obj);
  }

  public void rebind(String name, Object obj) throws NamingException {
    if (obj instanceof Referenceable) {
      Reference ref = ((Referenceable) obj).getReference();
      map.put(name, ref);
    } else if (obj instanceof Serializable) {
      try {
        MarshalledObject<Object> mo = new MarshalledObject<Object>(obj);
        map.put(name, mo);
      } catch (java.io.IOException e) {
        throw new NamingException("Unable to serialize object to JNDI: " + e);
      }
    } else {
      throw new NamingException(
          "Object to store in JNDI is neither Referenceable nor Serializable");
    }
  }

  public void unbind(Name name) throws NamingException {
    unbind(name.get(0));
  }

  public void unbind(String name) throws NamingException {
    map.remove(name);
  }

  public void rename(Name oldName, Name newName) throws NamingException {
    rename(oldName.get(0), newName.get(0));
  }

  public void rename(String oldName, String newName) throws NamingException {
    map.put(newName, map.remove(oldName));
  }

  public NamingEnumeration<NameClassPair> list(Name name) throws NamingException {
    return null;
  }

  public NamingEnumeration<NameClassPair> list(String name) throws NamingException {
    return null;
  }

  public NamingEnumeration<Binding> listBindings(Name name) throws NamingException {
    return null;
  }

  public NamingEnumeration<Binding> listBindings(String name) throws NamingException {
    return null;
  }

  public void destroySubcontext(Name name) throws NamingException {
  }

  public void destroySubcontext(String name) throws NamingException {
  }

  public Context createSubcontext(Name name) throws NamingException {
    return null;
  }

  public Context createSubcontext(String name) throws NamingException {
    return null;
  }

  public Object lookupLink(Name name) throws NamingException {
    return null;
  }

  public Object lookupLink(String name) throws NamingException {
    return null;
  }

  public NameParser getNameParser(Name name) throws NamingException {
    return null;
  }

  public NameParser getNameParser(String name) throws NamingException {
    return null;
  }

  public Name composeName(Name name, Name prefix) throws NamingException {
    return null;
  }

  public String composeName(String name, String prefix)
      throws NamingException {
    return null;
  }

  public Object addToEnvironment(String propName, Object propVal)
      throws NamingException {
    return null;
  }

  public Object removeFromEnvironment(String propName)
      throws NamingException {
    return null;
  }

  public Hashtable<?, ?> getEnvironment() throws NamingException {
    return null;
  }

  public void close() throws NamingException {
  }

  public String getNameInNamespace() throws NamingException {
    return null;
  }
}
