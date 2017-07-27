/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import org.postgresql.core.PGStream;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

import java.io.IOException;
import java.security.PrivilegedAction;
import java.util.logging.Level;
import java.util.logging.Logger;

class GssAction implements PrivilegedAction<Exception> {

  private static final Logger LOGGER = Logger.getLogger(GssAction.class.getName());
  private final PGStream pgStream;
  private final String host;
  private final String user;
  private final String kerberosServerName;
  private final boolean useSpnego;
  private final GSSCredential clientCredentials;


  GssAction(PGStream pgStream, GSSCredential clientCredentials, String host, String user,
      String kerberosServerName, boolean useSpnego) {
    this.pgStream = pgStream;
    this.clientCredentials = clientCredentials;
    this.host = host;
    this.user = user;
    this.kerberosServerName = kerberosServerName;
    this.useSpnego = useSpnego;
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

  public Exception run() {

    try {

      GSSManager manager = GSSManager.getInstance();
      GSSCredential clientCreds = null;
      Oid[] desiredMechs = new Oid[1];
      if (clientCredentials == null) {
        if (useSpnego && hasSpnegoSupport(manager)) {
          desiredMechs[0] = new Oid("1.3.6.1.5.5.2");
        } else {
          desiredMechs[0] = new Oid("1.2.840.113554.1.2.2");
        }
        GSSName clientName = manager.createName(user, GSSName.NT_USER_NAME);
        clientCreds = manager.createCredential(clientName, 8 * 3600, desiredMechs,
            GSSCredential.INITIATE_ONLY);
      } else {
        desiredMechs[0] = new Oid("1.2.840.113554.1.2.2");
        clientCreds = clientCredentials;
      }

      GSSName serverName =
          manager.createName(kerberosServerName + "@" + host, GSSName.NT_HOSTBASED_SERVICE);

      GSSContext secContext = manager.createContext(serverName, desiredMechs[0], clientCreds,
          GSSContext.DEFAULT_LIFETIME);
      secContext.requestMutualAuth(true);

      byte[] inToken = new byte[0];
      byte[] outToken = null;

      boolean established = false;
      while (!established) {
        outToken = secContext.initSecContext(inToken, 0, inToken.length);


        if (outToken != null) {
          LOGGER.log(Level.FINEST, " FE=> Password(GSS Authentication Token)");

          pgStream.sendChar('p');
          pgStream.sendInteger4(4 + outToken.length);
          pgStream.send(outToken);
          pgStream.flush();
        }

        if (!secContext.isEstablished()) {
          int response = pgStream.receiveChar();
          // Error
          switch (response) {
            case 'E':
              int l_elen = pgStream.receiveInteger4();
              ServerErrorMessage l_errorMsg
                  = new ServerErrorMessage(pgStream.receiveErrorString(l_elen - 4));

              LOGGER.log(Level.FINEST, " <=BE ErrorMessage({0})", l_errorMsg);

              return new PSQLException(l_errorMsg);
            case 'R':
              LOGGER.log(Level.FINEST, " <=BE AuthenticationGSSContinue");
              int len = pgStream.receiveInteger4();
              int type = pgStream.receiveInteger4();
              // should check type = 8
              inToken = pgStream.receive(len - 8);
              break;
            default:
              // Unknown/unexpected message type.
              return new PSQLException(GT.tr("Protocol error.  Session setup failed."),
                  PSQLState.CONNECTION_UNABLE_TO_CONNECT);
          }
        } else {
          established = true;
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
