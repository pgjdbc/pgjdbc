package org.postgresql.hostchooser;

import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;

public class CustomHostChooserManager {
  private final Map<HostChooser, String> hostChooserMap = new WeakHashMap<>();
  private static CustomHostChooserManager instance_ = getInstance();

  private CustomHostChooserManager() {

  }

  private static class UrlProperty {
    UrlProperty(String url, Properties info) {

    }

  }

  public static CustomHostChooserManager getInstance() {
    if (instance_ == null) {
      synchronized (CustomHostChooserManager.class) {
        if (instance_ == null) {
          instance_ = new CustomHostChooserManager();
        }
      }
    }
    return instance_;
  }

  public HostChooser getHostChooser(String customImplClassName) {
    for (Map.Entry e : hostChooserMap.entrySet()) {
      HostChooser hc = (HostChooser) e.getKey();
      String hcClazzName = hc.getClass().getName();
      if (hcClazzName.equals(customImplClassName)) {
        return hc;
      }
    }
    return null;
  }

  public HostChooser getOrCreateHostChooser(String url, Properties info, String customImplClass) throws PSQLException {
    UrlProperty key = new UrlProperty(url, info);
    HostChooser hc = getHostChooser(customImplClass);
    if (hc == null) {
      synchronized (this) {
        hc = getHostChooser(customImplClass);
        if (hc == null) {
          hc = instantiateCustomHostChooser(customImplClass, url, info);
          hostChooserMap.put(hc, customImplClass);
        }
      }
    }
    return hc;
  }

  private HostChooser instantiateCustomHostChooser(String customImplClass, String url,
      Properties info) throws PSQLException {
    Throwable t = null;
    String exMsg = null;
    try {
      // INVALID_NAME
      Class<?> clazz = Class.forName(customImplClass);
      HostChooser hc = (HostChooser) clazz.getDeclaredConstructor().newInstance();
      hc.init(url, info);
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
