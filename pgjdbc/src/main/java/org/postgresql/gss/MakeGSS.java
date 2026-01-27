/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.PGProperty;
import org.postgresql.core.PGStream;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.ietf.jgss.GSSCredential;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.security.PrivilegedAction;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;

public class MakeGSS {
  private static final Logger LOGGER = Logger.getLogger(MakeGSS.class.getName());
  private static final @Nullable MethodHandle SUBJECT_CURRENT;
  private static final @Nullable MethodHandle ACCESS_CONTROLLER_GET_CONTEXT;
  private static final @Nullable MethodHandle SUBJECT_GET_SUBJECT;
  // Java <18
  private static final @Nullable MethodHandle SUBJECT_DO_AS;
  // Java 18+, see https://bugs.openjdk.org/browse/JDK-8267108
  private static final @Nullable MethodHandle SUBJECT_CALL_AS;

  static {
    MethodHandle subjectCurrent = null;
    try {
      subjectCurrent = MethodHandles.lookup()
          .findStatic(Subject.class, "current", MethodType.methodType(Subject.class));
    } catch (NoSuchMethodException | IllegalAccessException ignore) {
      // E.g. pre Java 18
    }
    SUBJECT_CURRENT = subjectCurrent;

    MethodHandle accessControllerGetContext = null;
    MethodHandle subjectGetSubject = null;

    try {
      Class<?> accessControllerClass = Class.forName("java.security.AccessController");
      Class<?> accessControlContextClass =
          Class.forName("java.security.AccessControlContext");
      accessControllerGetContext = MethodHandles.lookup()
          .findStatic(accessControllerClass, "getContext",
              MethodType.methodType(accessControlContextClass));
      subjectGetSubject = MethodHandles.lookup()
          .findStatic(Subject.class, "getSubject",
              MethodType.methodType(Subject.class, accessControlContextClass));
    } catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException ignore) {
      // E.g. pre Java 18+
    }

    ACCESS_CONTROLLER_GET_CONTEXT = accessControllerGetContext;
    SUBJECT_GET_SUBJECT = subjectGetSubject;

    MethodHandle subjectDoAs = null;
    try {
      subjectDoAs = MethodHandles.lookup().findStatic(Subject.class, "doAs",
          MethodType.methodType(Object.class, Subject.class, PrivilegedAction.class));
    } catch (NoSuchMethodException | IllegalAccessException ignore) {
      // E.g. Java 18+
    }
    SUBJECT_DO_AS = subjectDoAs;

    MethodHandle subjectCallAs = null;
    try {
      subjectCallAs = MethodHandles.lookup().findStatic(Subject.class, "callAs",
          MethodType.methodType(Object.class, Subject.class, Callable.class));
    } catch (NoSuchMethodException | IllegalAccessException ignore) {
      // E.g. Java < 18
    }
    SUBJECT_CALL_AS = subjectCallAs;
  }

  /**
   * Use {@code Subject.current()} in Java 18+, and
   * {@code Subject.getSubject(AccessController.getContext())} in Java before 18.
   * @return current Subject or null
   */
  @SuppressWarnings("deprecation")
  private static @Nullable Subject getCurrentSubject() {
    try {
      if (SUBJECT_CURRENT != null) {
        return (Subject) SUBJECT_CURRENT.invokeExact();
      }
      if (SUBJECT_GET_SUBJECT == null || ACCESS_CONTROLLER_GET_CONTEXT == null) {
        return null;
      }
      return (Subject) SUBJECT_GET_SUBJECT.invoke(
          ACCESS_CONTROLLER_GET_CONTEXT.invoke()
      );
    } catch (Throwable e) {
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      }
      if (e instanceof Error) {
        throw (Error) e;
      }
      throw new RuntimeException(e);
    }
  }

  public static void authenticate(boolean encrypted,
      PGStream pgStream, String host, String user, char @Nullable [] password,
      @Nullable String jaasApplicationName, @Nullable String kerberosServerName,
      boolean useSpnego, boolean jaasLogin, boolean gssUseDefaultCreds,
      boolean logServerErrorDetail)
          throws IOException, PSQLException {
    LOGGER.log(Level.FINEST, " <=BE AuthenticationReqGSS");

    if (jaasApplicationName == null) {
      jaasApplicationName = PGProperty.JAAS_APPLICATION_NAME.getDefaultValue();
    }
    if (kerberosServerName == null) {
      kerberosServerName = "postgres";
    }

    @Nullable Exception result;
    try {
      boolean performAuthentication = jaasLogin;

      //Check if we can get credential from subject to avoid login.
      Subject sub = getCurrentSubject();
      if (sub != null) {
        Set<GSSCredential> gssCreds = sub.getPrivateCredentials(GSSCredential.class);
        if (gssCreds != null && !gssCreds.isEmpty()) {
          performAuthentication = false;
        }
      }
      if (performAuthentication) {
        LoginContext lc = new LoginContext(castNonNull(jaasApplicationName), new GSSCallbackHandler(user, password));
        lc.login();
        sub = lc.getSubject();
      }

      PrivilegedAction<@Nullable Exception> action;
      if ( encrypted ) {
        action = new GssEncAction(pgStream, sub, host, user,
            kerberosServerName, useSpnego, gssUseDefaultCreds, logServerErrorDetail);
      } else {
        action = new GssAction(pgStream, sub, host, user,
            kerberosServerName, useSpnego, gssUseDefaultCreds, logServerErrorDetail);
      }
      //noinspection ConstantConditions
      @SuppressWarnings({"cast.unsafe", "assignment"})
      @NonNull Subject subject = sub;
      if (SUBJECT_DO_AS != null) {
        result = (Exception) SUBJECT_DO_AS.invoke(subject, action);
      } else if (SUBJECT_CALL_AS != null) {
        //noinspection ConstantConditions,unchecked
        result = (Exception) SUBJECT_CALL_AS.invoke(subject, action);
      } else {
        throw new PSQLException(
            GT.tr("Neither Subject.doAs (Java before 18) nor Subject.callAs (Java 18+) method found"),
            PSQLState.OBJECT_NOT_IN_STATE);
      }
    } catch (Throwable e) {
      throw new PSQLException(GT.tr("GSS Authentication failed"), PSQLState.CONNECTION_FAILURE, e);
    }

    if (result instanceof IOException) {
      throw (IOException) result;
    } else if (result instanceof PSQLException) {
      throw (PSQLException) result;
    } else if (result != null) {
      throw new PSQLException(GT.tr("GSS Authentication failed"), PSQLState.CONNECTION_FAILURE,
          result);
    }

  }

}
