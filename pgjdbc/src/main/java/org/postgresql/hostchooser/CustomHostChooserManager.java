/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;

/**
 * This is a singleton class that manages all the custom host chooser. It uses a weakHashmap to
 * store the instance of the custom {@link HostChooser}. It uses the {@link HostChooserUrlProperty}
 * as the key. The weakHashMap is used so that the instance gets garbage collected when all the
 * created with that custom host chooser is also closed and garbage collected.
 */
public class CustomHostChooserManager {
  private final Map<HostChooserUrlProperty, HostChooser> hostChooserMap =
      new WeakHashMap<>();
  private static CustomHostChooserManager instance = getInstance();

  private CustomHostChooserManager() {

  }

  /**
   * A class which holds the url, info and the custom host chooser impl class name and is used
   * by the {@link CustomHostChooserManager} as a key for the hostChooserMap.
   */
  public static class HostChooserUrlProperty {
    private final String url;
    private final Properties info;
    private final String implStr;

    public HostChooserUrlProperty(String url, Properties info, String implStr) {
      this.url = url;
      this.info = info;
      this.implStr = implStr;
    }

    public String getUrl() {
      return this.url;
    }

    public String getImpl() {
      return this.implStr;
    }

    public Properties getProps() {
      return this.info;
    }

    public boolean equals(@Nullable Object other) {
      if (!(other instanceof HostChooserUrlProperty)) {
        return false;
      }
      HostChooserUrlProperty otherHcProps = (HostChooserUrlProperty) other;
      return this.url.equals(otherHcProps.url)
          && this.info.equals(otherHcProps.info)
          && this.implStr.equals(otherHcProps.implStr);
    }

    public int hashCode() {
      return url.hashCode() ^ info.hashCode() ^ implStr.hashCode();
    }
  }

  public static CustomHostChooserManager getInstance() {
    if (instance == null) {
      synchronized (CustomHostChooserManager.class) {
        if (instance == null) {
          instance = new CustomHostChooserManager();
        }
      }
    }
    return instance;
  }

  public @Nullable HostChooser getHostChooser(HostChooserUrlProperty customImplClassName) {
    if (hostChooserMap.containsKey(customImplClassName)) {
      return hostChooserMap.get(customImplClassName);
    }
    return null;
  }

  public HostChooser getOrCreateHostChooser(String url, Properties info, String customImplClass,
      HostRequirement targetServerType) throws PSQLException {
    HostChooserUrlProperty key = new HostChooserUrlProperty(url, info, customImplClass);
    HostChooser hc = getHostChooser(key);
    if (hc == null) {
      synchronized (this) {
        hc = getHostChooser(key);
        if (hc == null) {
          hc = instantiateCustomHostChooser(customImplClass, url, info, targetServerType);
          hostChooserMap.put(key, hc);
        }
      }
    }
    return hc;
  }

  private HostChooser instantiateCustomHostChooser(String customImplClass, String url,
      Properties info, HostRequirement targetServerType) throws PSQLException {
    Throwable t = null;
    String exMsg = null;
    try {
      // INVALID_NAME
      Class<?> clazz = Class.forName(customImplClass);
      HostChooser hc = (HostChooser) clazz.getDeclaredConstructor().newInstance();
      hc.init(url, info, targetServerType);
      return hc;
    } catch (ClassNotFoundException e) {
      exMsg = "Class not found " + customImplClass;
      t = e;
    } catch (InstantiationException e) {
      exMsg = "Class " + customImplClass + " cannot be instantiated (abstract class or "
          + "interface?).";
      t = e;
    } catch (IllegalAccessException e) {
      exMsg = "Illegal access: Ensure the constructor is public.";
      t = e;
    } catch (NoSuchMethodException e) {
      exMsg = "No no-arg constructor found in class " + customImplClass;
      t = e;
    } catch (InvocationTargetException e) {
      exMsg = "Constructor threw an exception: " + e.getCause();
      t = e;
    }
    throw new PSQLException(exMsg, PSQLState.INVALID_NAME, t);
  }
}
