/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Util {

  private static final ConcurrentMap<Class<?>, Class<?>[]> getImplementedInterfacesCache = new ConcurrentHashMap<>();
  private static final ConcurrentMap<Class<?>, Boolean> isJdbcInterfaceCache = new ConcurrentHashMap<>();

  /**
   * Check whether the given package is a JDBC package
   *
   * @param packageName the name of the package to analyze
   * @return true if the given package is a JDBC package
   */
  public static boolean isJdbcPackage(@Nullable String packageName) {
    return packageName != null
        && (packageName.startsWith("java.sql")
        || packageName.startsWith("javax.sql")
        || packageName.startsWith("org.postgresql"));
  }

  /**
   * Check whether the given class implements a JDBC interface defined in a JDBC package. See {@link #isJdbcPackage(String)}
   * Calls to this function are cached for improved efficiency.
   *
   * @param clazz the class to analyze
   * @return true if the given class implements a JDBC interface
   */
  public static boolean isJdbcInterface(Class<?> clazz) {
    if (Util.isJdbcInterfaceCache.containsKey(clazz)) {
      return (Util.isJdbcInterfaceCache.get(clazz));
    }

    if (clazz.isInterface()) {
      try {
        Package classPackage = clazz.getPackage();
        if (classPackage != null && isJdbcPackage(classPackage.getName())) {
          Util.isJdbcInterfaceCache.putIfAbsent(clazz, true);
          return true;
        }
      } catch (Exception ex) {
        // Ignore any exceptions since they're caused by runtime-generated classes, or due to class load issues.
      }
    }

    for (Class<?> iface : clazz.getInterfaces()) {
      if (isJdbcInterface(iface)) {
        Util.isJdbcInterfaceCache.putIfAbsent(clazz, true);
        return true;
      }
    }

    if (clazz.getSuperclass() != null && isJdbcInterface(clazz.getSuperclass())) {
      Util.isJdbcInterfaceCache.putIfAbsent(clazz, true);
      return true;
    }

    Util.isJdbcInterfaceCache.putIfAbsent(clazz, false);
    return false;
  }

  /**
   * Get the {@link Class} objects corresponding to the interfaces implemented by the given class. Calls to this function
   * are cached for improved efficiency.
   *
   * @param clazz the class to analyze
   * @return the interfaces implemented by the given class
   */
  public static Class<?>[] getImplementedInterfaces(Class<?> clazz) {
    Class<?>[] implementedInterfaces = Util.getImplementedInterfacesCache.get(clazz);
    if (implementedInterfaces != null) {
      return implementedInterfaces;
    }

    Set<Class<?>> interfaces = new LinkedHashSet<>();
    Class<?> superClass = clazz;
    do {
      Collections.addAll(interfaces, superClass.getInterfaces());
    } while ((superClass = superClass.getSuperclass()) != null);

    implementedInterfaces = interfaces.toArray(new Class<?>[0]);
    Class<?>[] oldValue = Util.getImplementedInterfacesCache.putIfAbsent(clazz, implementedInterfaces);
    if (oldValue != null) {
      implementedInterfaces = oldValue;
    }

    return implementedInterfaces;
  }

  /**
   * For the given {@link Throwable}, return a formatted string representation of the stack trace. This method is
   * provided for logging purposes.
   *
   * @param t the throwable containing the stack trace that we want to transform into a string
   * @param callingClass the class that is calling this method
   * @return the formatted string representation of the stack trace attached to the given {@link Throwable}
   */
  public static String stackTraceToString(Throwable t, Class callingClass) {
    StringBuilder buffer = new StringBuilder();
    buffer.append("\n\n========== [");
    buffer.append(callingClass.getName());
    buffer.append("]: Exception Detected: ==========\n\n");

    buffer.append(t.getClass().getName());

    String exceptionMessage = t.getMessage();

    if (exceptionMessage != null) {
      buffer.append("Message: ");
      buffer.append(exceptionMessage);
    }

    StringWriter out = new StringWriter();

    PrintWriter printOut = new PrintWriter(out);

    t.printStackTrace(printOut);

    buffer.append("Stack Trace:\n\n");
    buffer.append(out.toString());
    buffer.append("============================\n\n\n");

    return buffer.toString();
  }

  /**
   * Check if the supplied string is null or empty
   *
   * @param s the string to analyze
   * @return true if the supplied string is null or empty
   */
  @EnsuresNonNullIf(expression = "#1", result = false)
  public static boolean isNullOrEmpty(@Nullable String s) {
    return s == null || s.equals("");
  }

  public static <T> List<T> loadClasses(String extensionClassNames, String errorMessage) throws InstantiationException {

    List<T> instances = new LinkedList<>();
    List<String> interceptorsToCreate = split(extensionClassNames, ",", true);
    String className = null;

    try {
      for (int i = 0, s = interceptorsToCreate.size(); i < s; i++) {
        className = interceptorsToCreate.get(i);
        @SuppressWarnings("unchecked")
        T instance = (T) Class.forName(className).newInstance();

        instances.add(instance);
      }

    } catch (Throwable t) {
      throw new InstantiationException(String.format(errorMessage, className));
    }

    return instances;
  }

  /**
   * Splits stringToSplit into a list, using the given delimiter
   *
   * @param stringToSplit
   *            the string to split
   * @param delimiter
   *            the string to split on
   * @param trim
   *            should the split strings be whitespace trimmed?
   *
   * @return the list of strings, split by delimiter
   *
   * @throws IllegalArgumentException
   *             if an error occurs
   */
  public static List<String> split(String stringToSplit, String delimiter, boolean trim) {
    if (stringToSplit == null) {
      return new ArrayList<>();
    }

    if (delimiter == null) {
      throw new IllegalArgumentException();
    }

    String[] tokens = stringToSplit.split(delimiter, -1);
    Stream<String> tokensStream = Arrays.asList(tokens).stream();
    if (trim) {
      tokensStream = tokensStream.map(String::trim);
    }
    return tokensStream.collect(Collectors.toList());
  }

}
