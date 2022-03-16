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

import org.checkerframework.checker.nullness.qual.Nullable;
import org.ietf.jgss.GSSCredential;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;

public class MakeGSS {
  private static final Logger LOGGER = Logger.getLogger(MakeGSS.class.getName());

  public static void authenticate(boolean encrypted,
      PGStream pgStream, String host, String user, char @Nullable [] password,
      @Nullable String jaasApplicationName, @Nullable String kerberosServerName,
      boolean useSpnego, boolean jaasLogin,
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
      Subject sub = Subject.getSubject(AccessController.getContext());
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

      if ( encrypted ) {
        PrivilegedAction<@Nullable Exception> action = new GssEncAction(pgStream, sub, host, user,
            kerberosServerName, useSpnego, logServerErrorDetail);

        result = Subject.doAs(sub, action);
      } else {
        PrivilegedAction<@Nullable Exception> action = new GssAction(pgStream, sub, host, user,
            kerberosServerName, useSpnego, logServerErrorDetail);

        result = Subject.doAs(sub, action);
      }
    } catch (Exception e) {
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
