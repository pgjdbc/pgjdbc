/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager;

import org.postgresql.util.Util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.concurrent.Callable;

public class WrapperUtils {

  public static synchronized <T> T executeWithPlugins(ConnectionPluginManager pluginManager,
      Class<?> methodInvokeOn, String methodName,
      Callable<T> executeSqlFunc, Object... args) throws SQLException {

    Object[] argsCopy = args == null ? null : Arrays.copyOf(args, args.length);

    T result;
    try {
      result = pluginManager.execute_SQLException(
          methodInvokeOn,
          methodName,
          executeSqlFunc,
          argsCopy);
      result = wrapWithProxyIfNeeded(result, pluginManager);
    } catch (SQLException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return result;
  }

  public static synchronized <T> T executeWithPlugins_SQLClientInfoException(
      ConnectionPluginManager pluginManager,
      Class<?> methodInvokeOn, String methodName,
      Callable<T> executeSqlFunc, Object... args) throws SQLClientInfoException {

    Object[] argsCopy = args == null ? null : Arrays.copyOf(args, args.length);

    T result;
    try {
      result = pluginManager.execute_SQLClientInfoException(
          methodInvokeOn,
          methodName,
          executeSqlFunc,
          argsCopy);
      result = wrapWithProxyIfNeeded(result, pluginManager);
    } catch (SQLClientInfoException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return result;
  }

  protected static @Nullable <T> T wrapWithProxyIfNeeded(@Nullable T toProxy,
      ConnectionPluginManager pluginManager) {
    if (toProxy == null) {
      return null;
    }

    if (toProxy instanceof ConnectionWrapper
        || toProxy instanceof CallableStatementWrapper
        || toProxy instanceof PreparedStatementWrapper
        || toProxy instanceof StatementWrapper
        || toProxy instanceof ResultSetWrapper) {
      return toProxy;
    }

    if (toProxy instanceof Connection) {
      throw new UnsupportedOperationException("Shouldn't be here");
    }

    if (toProxy instanceof CallableStatement) {
      return (T) new CallableStatementWrapper((CallableStatement) toProxy, pluginManager);
    }

    if (toProxy instanceof PreparedStatement) {
      return (T) new PreparedStatementWrapper((PreparedStatement) toProxy, pluginManager);
    }

    if (toProxy instanceof Statement) {
      return (T) new StatementWrapper((Statement) toProxy, pluginManager);
    }

    if (toProxy instanceof ResultSet) {
      return (T) new ResultSetWrapper((ResultSet) toProxy, pluginManager);
    }

    if (!Util.isJdbcInterface(toProxy.getClass())) {
      return toProxy;
    }

    // Add more custom wrapper support here

    Class<?> toProxyClass = toProxy.getClass();
    return (T) java.lang.reflect.Proxy.newProxyInstance(toProxyClass.getClassLoader(),
        Util.getImplementedInterfaces(toProxyClass),
        new WrapperUtils.Proxy(pluginManager, toProxy));
  }

  /**
   * This class is a proxy for objects created through the proxied connection (for example,
   * {@link java.sql.Statement} and
   * {@link java.sql.ResultSet}. Similarly to ClusterAwareConnectionProxy, this proxy class
   * monitors the underlying object
   * for communications exceptions and initiates failover when required.
   */
  public static class Proxy implements InvocationHandler {
    static final String METHOD_EQUALS = "equals";
    static final String METHOD_HASH_CODE = "hashCode";

    Object invocationTarget;
    ConnectionPluginManager pluginManager;

    Proxy(ConnectionPluginManager pluginManager, Object invocationTarget) {
      this.pluginManager = pluginManager;
      this.invocationTarget = invocationTarget;
    }

    public @Nullable Object invoke(Object proxy, Method method, @Nullable Object[] args)
        throws Throwable {
      if (METHOD_EQUALS.equals(method.getName()) && args != null && args[0] != null) {
        return args[0].equals(this);
      }

      if (METHOD_HASH_CODE.equals(method.getName())) {
        return this.hashCode();
      }

      synchronized (WrapperUtils.Proxy.this) {
        Object result;
        Object[] argsCopy = args == null ? null : Arrays.copyOf(args, args.length);

        try {
          result = this.pluginManager.execute(
              this.invocationTarget.getClass(),
              method.getName(),
              () -> method.invoke(this.invocationTarget, args),
              argsCopy);
          result = wrapWithProxyIfNeeded(method.getReturnType(), result);
        } catch (InvocationTargetException e) {
          throw e.getTargetException() == null ? e : e.getTargetException();
        } catch (IllegalStateException e) {
          throw e.getCause() == null ? e : e.getCause();
        }

        return result;
      }
    }

    protected @Nullable Object wrapWithProxyIfNeeded(Class<?> returnType,
        @Nullable Object toProxy) {
      if (toProxy == null) {
        return null;
      }

      if (toProxy instanceof ConnectionWrapper
          || toProxy instanceof CallableStatementWrapper
          || toProxy instanceof PreparedStatementWrapper
          || toProxy instanceof StatementWrapper
          || toProxy instanceof ResultSetWrapper) {
        return toProxy;
      }

      // Add custom wrapper support here

      if (!Util.isJdbcInterface(returnType)) {
        return toProxy;
      }

      Class<?> toProxyClass = toProxy.getClass();
      return java.lang.reflect.Proxy.newProxyInstance(toProxyClass.getClassLoader(),
          Util.getImplementedInterfaces(toProxyClass),
          new WrapperUtils.Proxy(this.pluginManager, toProxy));
    }

  }
}
