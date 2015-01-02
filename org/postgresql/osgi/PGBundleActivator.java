/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.osgi;

import java.util.Properties;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.jdbc.DataSourceFactory;
import org.postgresql.Driver;

/**
 * This class is an OSGi Bundle Activator and should only be used internally by the OSGi Framework
 */
public class PGBundleActivator implements BundleActivator
{
    private ServiceRegistration _registration;

    public void start(BundleContext context) throws Exception
    {
        Properties properties = new Properties();
        properties.put(DataSourceFactory.OSGI_JDBC_DRIVER_CLASS, Driver.class.getName());
        properties.put(DataSourceFactory.OSGI_JDBC_DRIVER_NAME, "Postgresql JDBC Driver");
        properties.put(DataSourceFactory.OSGI_JDBC_DRIVER_VERSION, Driver.getVersion());
        _registration = context.registerService(DataSourceFactory.class.getName(),
                                                new PGDataSourceFactory(),
                                                properties);
    }

    public void stop(BundleContext context) throws Exception
    {
        if (_registration != null)
        {
            _registration.unregister();
        }

        if (Driver.isRegistered())
        {
            Driver.deregister();
        }
    }
}
