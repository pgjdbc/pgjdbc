/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import org.postgresql.core.PGStream;
import org.postgresql.exception.PgSqlState;
import org.postgresql.util.GT;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.ietf.jgss.GSSCredential;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;

public class MakeGSS {
  private static final Logger LOGGER = Logger.getLogger(MakeGSS.class.getName());

  public static void authenticate(boolean encrypted,
      PGStream pgStream, String host, String user, @Nullable String password,
      @Nullable String jaasApplicationName, @Nullable String kerberosServerName,
      boolean useSpnego, boolean jaasLogin,
      boolean logServerErrorDetail)
          throws IOException, SQLException {
    LOGGER.log(Level.FINEST, " <=BE AuthenticationReqGSS");

    if (jaasApplicationName == null) {
      jaasApplicationName = "pgjdbc";
    }
    if (kerberosServerName == null) {
      kerberosServerName = "postgres";
    }

    @Nullable Exception result;
    try {
      boolean performAuthentication = jaasLogin;
      GSSCredential gssCredential = null;
      Subject sub = Subject.getSubject(AccessController.getContext());
      if (sub != null) {
        Set<GSSCredential> gssCreds = sub.getPrivateCredentials(GSSCredential.class);
        if (gssCreds != null && !gssCreds.isEmpty()) {
          gssCredential = gssCreds.iterator().next();
          performAuthentication = false;
        }
      }
      if (performAuthentication) {
        LoginContext lc =
            new LoginContext(jaasApplicationName, new GSSCallbackHandler(user, password));
        lc.login();
        sub = lc.getSubject();
      }
      if ( encrypted ) {
        PrivilegedAction<@Nullable Exception> action = new GssEncAction(pgStream, gssCredential, host, user,
            kerberosServerName, useSpnego, logServerErrorDetail);

        result = Subject.doAs(sub, action);
      } else {
        PrivilegedAction<@Nullable Exception> action = new GssAction(pgStream, gssCredential, host, user,
            kerberosServerName, useSpnego, logServerErrorDetail);

        result = Subject.doAs(sub, action);
      }
    } catch (Exception e) {
      throw new SQLInvalidAuthorizationSpecException(GT.tr("GSS Authentication failed"),
          PgSqlState.INVALID_AUTHORIZATION_SPECIFICATION, e);
    }

    if (result instanceof IOException) {
      throw (IOException) result;
    } else if (result instanceof SQLInvalidAuthorizationSpecException) {
      throw (SQLInvalidAuthorizationSpecException) result;
    } else if (result != null) {
      throw new SQLInvalidAuthorizationSpecException(GT.tr("GSS Authentication failed"),
          PgSqlState.INVALID_AUTHORIZATION_SPECIFICATION,
          result);
    }

  }

}
