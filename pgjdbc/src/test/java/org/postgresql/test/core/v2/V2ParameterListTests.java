/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2003-2016, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */

package org.postgresql.test.core.v2;

import org.postgresql.core.ParameterList;

import junit.framework.TestCase;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;


/**
 * Test cases to make sure the parameterlist implementation works as expected.
 * Test case located in different package as the package private implementation.
 * Reflection used to crack open and access the class. Not pretty but works.
 *
 * @author Jeremy Whiting jwhiting@redhat.com
 *
 */
public class V2ParameterListTests extends TestCase {

  public void testMergeOfParameterLists() {
    try {
      ClassLoader cl = this.getClass().getClassLoader();
      Class cls = Class.forName("org.postgresql.core.v2.SimpleParameterList",
          true, cl);
      Constructor c = cls.getDeclaredConstructor(Integer.TYPE, Boolean.TYPE);
      c.setAccessible(true);
      Object o1SPL = c
          .newInstance(new Object[] { new Integer(8), Boolean.TRUE });
      assertNotNull(o1SPL);
      Method msetIP = cls.getMethod("setIntParameter", Integer.TYPE,
          Integer.TYPE);
      assertNotNull(msetIP);
      msetIP.setAccessible(true);
      msetIP.invoke(o1SPL, 1, 1);
      msetIP.invoke(o1SPL, 2, 2);
      msetIP.invoke(o1SPL, 3, 3);
      msetIP.invoke(o1SPL, 4, 4);

      Object o2SPL = c
          .newInstance(new Object[] { new Integer(4), Boolean.TRUE });
      msetIP.invoke(o2SPL, 1, 5);
      msetIP.invoke(o2SPL, 2, 6);
      msetIP.invoke(o2SPL, 3, 7);
      msetIP.invoke(o2SPL, 4, 8);
      Method mappendAll = cls.getMethod("appendAll", ParameterList.class);
      mappendAll.setAccessible(true);
      assertNotNull(mappendAll);
      mappendAll.invoke(o1SPL, o2SPL);
      Method mgetValues = cls.getMethod("getValues");
      mgetValues.setAccessible(true);

      Object values = mgetValues.invoke(o1SPL);
      assertNotNull(values);
      assertTrue(values instanceof Object[]);
      Object[] vals = (Object[]) values;
      assertNotNull(vals[0]);
      assertEquals("1", (String) vals[0]);
      assertNotNull(vals[1]);
      assertEquals("2", (String) vals[1]);
      assertNotNull(vals[2]);
      assertEquals("3", (String) vals[2]);
      assertNotNull(vals[3]);
      assertEquals("4", (String) vals[3]);
      assertNotNull(vals[4]);
      assertEquals("5", (String) vals[4]);
      assertNotNull(vals[5]);
      assertEquals("6", (String) vals[5]);
      assertNotNull(vals[6]);
      assertEquals("7", (String) vals[6]);
      assertNotNull(vals[7]);
      assertEquals("8", (String) vals[7]);
    } catch (ClassNotFoundException cnfe) {
      fail(cnfe.getMessage());
    } catch (NoSuchMethodException nsme) {
      fail(nsme.getMessage());
    } catch (InvocationTargetException ite) {
      fail(ite.getMessage());
    } catch (IllegalAccessException iae) {
      fail(iae.getMessage());
    } catch (InstantiationException ie) {
      fail(ie.getMessage());
    }
  }
}
