/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import org.postgresql.core.PGStream;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.ietf.jgss.GSSCredential;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.sql.SQLException;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;


public class MakeGSS {

  private static final Logger LOGGER = Logger.getLogger(MakeGSS.class.getName());

  public static void authenticate(PGStream pgStream, String host, String user, String password,
      String jaasApplicationName, String kerberosServerName, boolean useSpnego, boolean jaasLogin)
          throws IOException, SQLException {
    LOGGER.log(Level.FINEST, " <=BE AuthenticationReqGSS");

    if (jaasApplicationName == null) {
      jaasApplicationName = "pgjdbc";
    }
    if (kerberosServerName == null) {
      kerberosServerName = "postgres";
    }

    Exception result;
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
      PrivilegedAction<Exception> action = new GssAction(pgStream, gssCredential, host, user,
          kerberosServerName, useSpnego);

      result = Subject.doAs(sub, action);
    } catch (Exception e) {
      throw new PSQLException(GT.tr("GSS Authentication failed"), PSQLState.CONNECTION_FAILURE, e);
    }

    if (result instanceof IOException) {
      throw (IOException) result;
    } else if (result instanceof SQLException) {
      throw (SQLException) result;
    } else if (result != null) {
      throw new PSQLException(GT.tr("GSS Authentication failed"), PSQLState.CONNECTION_FAILURE,
          result);
    }

  }

}
