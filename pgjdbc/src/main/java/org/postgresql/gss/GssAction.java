/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import org.postgresql.core.PGStream;
import org.postgresql.core.PgMessageType;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;

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
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.Subject;

class GssAction implements PrivilegedAction<@Nullable Exception>, Callable<@Nullable Exception> {

  private static final Logger LOGGER = Logger.getLogger(GssAction.class.getName());
  private final PGStream pgStream;
  private final String host;
  private final String kerberosServerName;
  private final String user;
  private final boolean useSpnego;
  private final boolean gssUseDefaultCreds;
  private final @Nullable Subject subject;
  private final boolean logServerErrorDetail;

  GssAction(PGStream pgStream, @Nullable Subject subject, String host, String user,
      String kerberosServerName, boolean useSpnego, boolean gssUseDefaultCreds,
      boolean logServerErrorDetail) {
    this.pgStream = pgStream;
    this.subject = subject;
    this.host = host;
    this.user = user;
    this.kerberosServerName = kerberosServerName;
    this.useSpnego = useSpnego;
    this.gssUseDefaultCreds = gssUseDefaultCreds;
    this.logServerErrorDetail = logServerErrorDetail;
  }

  private static boolean hasSpnegoSupport(GSSManager manager) throws GSSException {
    Oid spnego = new Oid("1.3.6.1.5.5.2");
    Oid[] mechs = manager.getMechs();

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

      //Try to get credential from subject first.
      GSSCredential gssCredential = null;
      if (subject != null) {
        Set<GSSCredential> gssCreds = subject.getPrivateCredentials(GSSCredential.class);
        if (gssCreds != null && !gssCreds.isEmpty()) {
          gssCredential = gssCreds.iterator().next();
        }
      }

      //If failed to get credential from subject,
      //then call createCredential to create one.
      if (gssCredential == null) {
        if (useSpnego && hasSpnegoSupport(manager)) {
          desiredMechs[0] = new Oid("1.3.6.1.5.5.2");
        } else {
          desiredMechs[0] = new Oid("1.2.840.113554.1.2.2");
        }
        String principalName = this.user;
        if (subject != null) {
          Set<Principal> principals = subject.getPrincipals();
          Iterator<Principal> principalIterator = principals.iterator();

          Principal principal = null;
          if (principalIterator.hasNext()) {
            principal = principalIterator.next();
            principalName = principal.getName();
          }
        }

        if (gssUseDefaultCreds) {
          clientCreds = manager.createCredential(GSSCredential.INITIATE_ONLY);
        } else {
          GSSName clientName = manager.createName(principalName, GSSName.NT_USER_NAME);
          clientCreds = manager.createCredential(clientName, 8 * 3600, desiredMechs,
              GSSCredential.INITIATE_ONLY);
        }
      } else {
        desiredMechs[0] = new Oid("1.2.840.113554.1.2.2");
        clientCreds = gssCredential;
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

          pgStream.sendChar(PgMessageType.GSS_TOKEN_REQUEST);
          pgStream.sendInteger4(4 + outToken.length);
          pgStream.send(outToken);
          pgStream.flush();
        }

        if (!secContext.isEstablished()) {
          int response = pgStream.receiveChar();
          // Error
          switch (response) {
            case PgMessageType.ERROR_RESPONSE:
              int elen = pgStream.receiveInteger4();
              ServerErrorMessage errorMsg
                  = new ServerErrorMessage(pgStream.receiveErrorString(elen - 4));

              LOGGER.log(Level.FINEST, " <=BE ErrorMessage({0})", errorMsg);

              return new PSQLException(errorMsg, logServerErrorDetail);
            case PgMessageType.AUTHENTICATION_RESPONSE:
              LOGGER.log(Level.FINEST, " <=BE AuthenticationGSSContinue");
              int len = pgStream.receiveInteger4();
              @SuppressWarnings("unused")
              int type = pgStream.receiveInteger4(); // Specifies that this message contains GSSAPI or SSPI data
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

  @Override
  public @Nullable Exception call() throws Exception {
    return run();
  }
}
