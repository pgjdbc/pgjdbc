/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.osgi;

import org.postgresql.Driver;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.jdbc.DataSourceFactory;

import java.util.Dictionary;
import java.util.Hashtable;

/**
 * This class is an OSGi Bundle Activator and should only be used internally by the OSGi Framework.
 */
public class PGBundleActivator implements BundleActivator {
  private ServiceRegistration<?> _registration;

  public void start(BundleContext context) throws Exception {
    Dictionary<String, Object> properties = new Hashtable<String, Object>();
    properties.put(DataSourceFactory.OSGI_JDBC_DRIVER_CLASS, Driver.class.getName());
    properties.put(DataSourceFactory.OSGI_JDBC_DRIVER_NAME, org.postgresql.util.DriverInfo.DRIVER_NAME);
    properties.put(DataSourceFactory.OSGI_JDBC_DRIVER_VERSION, org.postgresql.util.DriverInfo.DRIVER_VERSION);
    try {
      _registration = context.registerService(DataSourceFactory.class.getName(),
          new PGDataSourceFactory(), properties);
    } catch (NoClassDefFoundError e) {
      String msg = e.getMessage();
      if (msg != null && msg.contains("org/osgi/service/jdbc/DataSourceFactory")) {
        if (!Boolean.getBoolean("pgjdbc.osgi.debug")) {
          return;
        }

        new IllegalArgumentException("Unable to load DataSourceFactory. "
            + "Will ignore DataSourceFactory registration. If you need one, "
            + "ensure org.osgi.enterprise is on the classpath", e).printStackTrace();
        // just ignore. Assume OSGi-enterprise is not loaded
        return;
      }
      throw e;
    }
  }

  public void stop(BundleContext context) throws Exception {
    if (_registration != null) {
      _registration.unregister();
      _registration = null;
    }

    if (Driver.isRegistered()) {
      Driver.deregister();
    }
  }
}
