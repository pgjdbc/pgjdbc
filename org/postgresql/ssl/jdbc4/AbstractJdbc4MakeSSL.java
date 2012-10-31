/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.ssl.jdbc4;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Properties;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.postgresql.core.Logger;
import org.postgresql.core.PGStream;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

public class AbstractJdbc4MakeSSL {
  
    /**
     * Instantiates a class using the appropriate constructor.
     * If a constructor with a single Propertiesparameter exists, it is
     * used. Otherwise, if tryString is true a constructor with
     * a single String argument is searched if it fails, or tryString is true
     * a no argument constructor is tried.
     * @param classname Nam of the class to instantiate
     * @param info parameter to pass as Properties
     * @param tryString weather to look for a single String argument constructor
     * @param stringarg parameter to pass as String
     * @return the instantiated class
     * @throws ClassNotFoundException
     * @throws SecurityException
     * @throws NoSuchMethodException
     * @throws IllegalArgumentException
     * @throws InstantiationException
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     */
    public static Object instantiate(String classname, Properties info, boolean tryString, String stringarg) 
        throws ClassNotFoundException, SecurityException, NoSuchMethodException, IllegalArgumentException,
        InstantiationException, IllegalAccessException, InvocationTargetException
    {
      Object[] args = {info};
      Constructor ctor = null;
      Class cls;
      cls = Class.forName(classname);
      try
      {         
          ctor = cls.getConstructor(new Class[]{Properties.class});
      }
      catch (NoSuchMethodException nsme)
      {
        if (tryString)
        {
          try
          {
              ctor = cls.getConstructor(new Class[]{String.class});
              args = new String[]{stringarg};
          }
          catch (NoSuchMethodException nsme2)
          {
            tryString = false;
          }
        }
        if (!tryString)
        {
          ctor = cls.getConstructor((Class[])null);
          args = null;          
        }
      }
      return ctor.newInstance(args);
    }
    
    public static void convert(PGStream stream, Properties info, Logger logger) throws PSQLException, IOException {
        logger.debug("converting regular socket connection to ssl");

        SSLSocketFactory factory;

        String sslmode = info.getProperty("sslmode");
        // Use the default factory if no specific factory is requested
        // unless sslmode is set
        String classname = info.getProperty("sslfactory");
        if (classname == null)
        {
          //If sslmode is set, use the libp compatible factory
          if (sslmode!=null)
          {
            factory = new LibPQFactory(info);
          }
          else
          {
            factory = (SSLSocketFactory)SSLSocketFactory.getDefault();
          }
        }
        else
        {
            try
            {
                factory = (SSLSocketFactory)instantiate(classname, info, true, info.getProperty("sslfactoryarg"));
            }
            catch (Exception e)
            {
                throw new PSQLException(GT.tr("The SSLSocketFactory class provided {0} could not be instantiated.", classname), PSQLState.CONNECTION_FAILURE, e);
            }
        }

        SSLSocket newConnection;
        try
        {
          newConnection = (SSLSocket)factory.createSocket(stream.getSocket(), stream.getHostSpec().getHost(), stream.getHostSpec().getPort(), true);
          newConnection.startHandshake(); //We must invoke manually, otherwise the exceptions are hidden
        }
        catch (IOException ex) {
          if (factory instanceof LibPQFactory)
          { //throw any KeyManager exception
            ((LibPQFactory)factory).throwKeyManagerException();
          }
          throw new PSQLException(GT.tr("SSL error: {0}", ex.getMessage()), PSQLState.CONNECTION_FAILURE, ex);
        }
        
        String sslhostnameverifier = info.getProperty("sslhostnameverifier");
        if (sslhostnameverifier!=null)
        {
          HostnameVerifier hvn;
          try
          {
            hvn = (HostnameVerifier)instantiate(sslhostnameverifier, info, false, null);
          }
          catch (Exception e)
          {
              throw new PSQLException(GT.tr("The HostnameVerifier class provided {0} could not be instantiated.", sslhostnameverifier), PSQLState.CONNECTION_FAILURE, e);
          }
          if (!hvn.verify(stream.getHostSpec().getHost(), newConnection.getSession()))
          {
            throw new PSQLException(GT.tr("The hostname {0} could not be verified by hostnameverifier {1}.", new Object[]{stream.getHostSpec().getHost(), sslhostnameverifier}), PSQLState.CONNECTION_FAILURE);
          }
        } else {
          if ("verify-full".equals(sslmode) && factory instanceof LibPQFactory)
          {
            if (!(((LibPQFactory)factory).verify(stream.getHostSpec().getHost(), newConnection.getSession())))
            {
              throw new PSQLException(GT.tr("The hostname {0} could not be verified.", stream.getHostSpec().getHost()), PSQLState.CONNECTION_FAILURE);
            }
          }

        }
        stream.changeSocket(newConnection);
    }

}


