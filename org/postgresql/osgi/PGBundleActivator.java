/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.osgi;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.postgresql.Driver;

/**
 * This class is an OSGi Bundle Activator and should only be used internally by the OSGi Framework
 */
public class PGBundleActivator implements BundleActivator
{
    private ServiceRegistration _registration;

    public void start(BundleContext context) throws Exception
    {
        if (!Driver.isRegistered())
        {
            Driver.register();
        }

        _registration = new PGDataSourceFactory().register(context);
    }

    public void stop(BundleContext context) throws Exception
    {
        if (_registration != null)
        {
            try
            {
                _registration.unregister();
            }
            catch (IllegalStateException e)
            {
                // continue: service has already been unregistered somewhere else but do not prevent correct stop
            }
            _registration = null;
        }

        if (Driver.isRegistered())
        {
            Driver.deregister();
        }
    }
}
