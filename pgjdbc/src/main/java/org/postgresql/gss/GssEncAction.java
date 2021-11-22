/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import org.postgresql.core.PGStream;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

import java.io.IOException;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.util.Iterator;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.Subject;

public class GssEncAction implements PrivilegedAction<@Nullable Exception> {
  private static final Logger LOGGER = Logger.getLogger(GssAction.class.getName());
  private final PGStream pgStream;
  private final String host;
  private final String user;
  private final String kerberosServerName;
  private final boolean useSpnego;
  private final Subject subject;
  private final boolean logServerErrorDetail;

  public GssEncAction(PGStream pgStream,  Subject subject,
      String host, String user,
      String kerberosServerName, boolean useSpnego, boolean logServerErrorDetail) {
    this.pgStream = pgStream;
    this.subject = subject;
    this.host = host;
    this.user = user;
    this.kerberosServerName = kerberosServerName;
    this.useSpnego = useSpnego;
    this.logServerErrorDetail = logServerErrorDetail;
  }

  private static boolean hasSpnegoSupport(GSSManager manager) throws GSSException {
    org.ietf.jgss.Oid spnego = new org.ietf.jgss.Oid("1.3.6.1.5.5.2");
    org.ietf.jgss.Oid[] mechs = manager.getMechs();

    for (Oid mech : mechs) {
      if (mech.equals(spnego)) {
        return true;
      }
    }

    return false;
  }

  @Override
  public @Nullable Exception run() {
    try {
      GSSManager manager = GSSManager.getInstance();
      GSSCredential clientCreds = null;
      Oid[] desiredMechs = new Oid[1];
      if (useSpnego && hasSpnegoSupport(manager)) {
        desiredMechs[0] = new Oid("1.3.6.1.5.5.2");
      } else {
        desiredMechs[0] = new Oid("1.2.840.113554.1.2.2");
      }
      Set<Principal> principals = subject.getPrincipals();
      Iterator<Principal> principalIterator = principals.iterator();
      Principal principal = null;
      if (principalIterator.hasNext()) {
        principal = principalIterator.next();
      } else {
        return new Exception("No principal found, unexpected error please report");
      }

      GSSName clientName = manager.createName(principal.getName(), GSSName.NT_USER_NAME);
      clientCreds = manager.createCredential(clientName, 8 * 3600, desiredMechs,
          GSSCredential.INITIATE_ONLY);

      GSSName serverName =
          manager.createName(kerberosServerName + "@" + host, GSSName.NT_HOSTBASED_SERVICE);

      GSSContext secContext = manager.createContext(serverName, desiredMechs[0], clientCreds,
          GSSContext.DEFAULT_LIFETIME);
      secContext.requestMutualAuth(true);
      secContext.requestConf(true);
      secContext.requestInteg(true);

      byte[] inToken = new byte[0];
      byte[] outToken = null;

      boolean established = false;
      while (!established) {
        outToken = secContext.initSecContext(inToken, 0, inToken.length);

        if (outToken != null) {
          LOGGER.log(Level.FINEST, " FE=> Password(GSS Authentication Token)");

          pgStream.sendInteger4(outToken.length);
          pgStream.send(outToken);
          pgStream.flush();
        }

        if (!secContext.isEstablished()) {
          int len = pgStream.receiveInteger4();
          // should check type = 8
          inToken = pgStream.receive(len);
        } else {
          established = true;
          pgStream.setSecContext(secContext);
        }
      }

    } catch (IOException e) {
      return e;
    } catch (GSSException gsse) {
      return new PSQLException(GT.tr("GSS Authentication failed"), PSQLState.CONNECTION_FAILURE,
          gsse);
    }

    return null;
  }

}
