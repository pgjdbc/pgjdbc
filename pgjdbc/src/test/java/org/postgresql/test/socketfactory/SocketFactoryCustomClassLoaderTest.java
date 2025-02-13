/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.socketfactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.util.Properties;

/**
 * Tests that the socket factory is loaded from the TCCL, and not from the same CL as the driver.
 */
public class SocketFactoryCustomClassLoaderTest {

  private static final String STRING_ARGUMENT = "name of a socket";

  private Connection conn;

  private ClassLoader oldTccl;

  @Before
  public void setUp() throws Exception {
    oldTccl = Thread.currentThread().getContextClassLoader();
    //this is an isolatedCL, that will define a new ClassLoaderCustomSocketFactory
    //everything else is loaded parent first
    Thread.currentThread().setContextClassLoader(new ClassLoader(oldTccl) {
      @Override
      public Class<?> loadClass(String name) throws ClassNotFoundException {
        return loadClass(name, false);
      }

      @Override
      protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.equals(ClassLoaderCustomSocketFactory.class.getName())) {
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          try (InputStream in = getClass().getClassLoader().getResourceAsStream(name.replace(".",
              "/") + ".class")) {
            int r;
            byte[] buf = new byte[1024];
            while ((r = in.read(buf)) > 0) {
              out.write(buf, 0, r);
            }
            return defineClass(name, out.toByteArray(), 0, out.size());
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        } else {
          return super.loadClass(name, resolve);
        }
      }
    });
    Properties properties = new Properties();
    properties.put(PGProperty.SOCKET_FACTORY.getName(),
        ClassLoaderCustomSocketFactory.class.getName());
    properties.put(PGProperty.SOCKET_FACTORY_ARG.getName(), STRING_ARGUMENT);
    conn = TestUtil.openDB(properties);
  }

  @After
  public void tearDown() throws Exception {
    Thread.currentThread().setContextClassLoader(oldTccl);
    TestUtil.closeDB(conn);
  }

  @Test
  public void testFactoryLoadedFromNewClassLoader() throws Exception {
    assertNotNull("Custom socket factory not null", Holder.customSocketFactory);
    //verify that we loaded the factory from a different CL
    assertNotEquals("Loaded from wrong CL", getClass().getClassLoader(), Holder.customSocketFactory.getClass().getClassLoader());
    assertFalse(Holder.customSocketFactory instanceof ClassLoaderCustomSocketFactory);
    assertNull("Instance of ClassLoaderCustomSocketFactory created in wrong ClassLoader",
        ClassLoaderCustomSocketFactory.getInstance());
  }

}
