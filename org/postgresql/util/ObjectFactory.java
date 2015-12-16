package org.postgresql.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Properties;

public class ObjectFactory {
    
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

}
