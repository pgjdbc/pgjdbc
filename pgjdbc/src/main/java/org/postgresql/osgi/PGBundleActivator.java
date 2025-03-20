/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.osgi;

import org.postgresql.Driver;
import org.postgresql.util.DriverInfo;

import org.checkerframework.checker.nullness.qual.Nullable;
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
  private @Nullable ServiceRegistration<?> registration;

  @Override
  public void start(BundleContext context) throws Exception {
    if (!Driver.isRegistered()) {
      Driver.register();
    }
    if (dataSourceFactoryExists()) {
      registerDataSourceFactory(context);
    }
  }

  private static boolean dataSourceFactoryExists() {
    try {
      Class.forName("org.osgi.service.jdbc.DataSourceFactory");
      return true;
    } catch (ClassNotFoundException ignored) {
      // DataSourceFactory does not exist => no reason to register the service
    }
    return false;
  }

  private void registerDataSourceFactory(BundleContext context) {
    @SuppressWarnings("JdkObsolete")
    Dictionary<String, Object> properties = new Hashtable<>();
    properties.put(DataSourceFactory.OSGI_JDBC_DRIVER_CLASS, Driver.class.getName());
    properties.put(DataSourceFactory.OSGI_JDBC_DRIVER_NAME,
        DriverInfo.DRIVER_NAME);
    properties.put(DataSourceFactory.OSGI_JDBC_DRIVER_VERSION,
        DriverInfo.DRIVER_VERSION);
    registration = context.registerService(DataSourceFactory.class,
        new PGDataSourceFactory(), properties);
  }

  @Override
  public void stop(BundleContext context) throws Exception {
    if (registration != null) {
      registration.unregister();
      registration = null;
    }

    if (Driver.isRegistered()) {
      Driver.deregister();
    }
  }
}
