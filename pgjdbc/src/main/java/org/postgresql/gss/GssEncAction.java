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
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.Subject;

public class GssEncAction implements PrivilegedAction<@Nullable Exception>, Callable<@Nullable Exception> {
  private static final Logger LOGGER = Logger.getLogger(GssAction.class.getName());
  // PQ_GSS_AUTH_BUFFER_SIZE (fe-secure-gssapi.c), 64 kB including the length word. Handshake
  // tokens are not bounded by PQ_GSS_MAX_PACKET_SIZE, which governs encrypted packets.
  private static final int MAX_HANDSHAKE_TOKEN_SIZE = 64 * 1024 - 4;
  private final PGStream pgStream;
  private final String host;
  private final String user;
  private final String kerberosServerName;
  private final boolean useSpnego;
  private final boolean gssUseDefaultCreds;
  private final @Nullable Subject subject;
  @SuppressWarnings("unused")
  private final boolean logServerErrorDetail;

  public GssEncAction(PGStream pgStream, @Nullable Subject subject,
      String host, String user,
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
      secContext.requestConf(true);
      secContext.requestInteg(true);

      return negotiate(secContext);
    } catch (IOException e) {
      return e;
    } catch (GSSException gsse) {
      return new PSQLException(GT.tr("GSS Authentication failed"), PSQLState.CONNECTION_FAILURE,
          gsse);
    }
  }

  /**
   * Exchanges tokens with the backend until the context is established, then switches the
   * stream to it. Kept apart from {@link #run()}, which builds the credentials and the context,
   * so a test can run the loop without a Kerberos realm.
   *
   * @param secContext the context to establish
   * @return null once established, or the exception to report
   * @throws GSSException if the context rejects a token
   * @throws IOException on an I/O error
   */
  @Nullable Exception negotiate(GSSContext secContext) throws GSSException, IOException {
    byte[] inToken = new byte[0];

    // A zero length token is a legal continuation, so a server that answers every token with
    // another would otherwise keep the client going indefinitely.
    for (int round = 0; round < PGStream.MAX_AUTH_ROUND_TRIPS; round++) {
      byte[] outToken = secContext.initSecContext(inToken, 0, inToken.length);

      if (outToken != null) {
        LOGGER.log(Level.FINEST, " FE=> Password(GSS Authentication Token)");

        pgStream.sendInteger4(outToken.length);
        pgStream.send(outToken);
        pgStream.flush();
      }

      if (secContext.isEstablished()) {
        pgStream.setSecContext(secContext);
        return null;
      }

      // The length here is the raw token size, not a self inclusive message length.
      int len = pgStream.receiveInteger4();
      if (len < 1 || len > MAX_HANDSHAKE_TOKEN_SIZE) {
        throw pgStream.protocolViolation(GT.tr(
            "Backend declared a GSS token of {0} bytes, the maximum is {1}.",
            String.valueOf(len), String.valueOf(MAX_HANDSHAKE_TOKEN_SIZE)));
      }
      inToken = pgStream.receive(len);
    }

    pgStream.setBroken();
    return new PSQLException(GT.tr(
        "GSS encryption handshake did not complete within {0} round trips.",
        PGStream.MAX_AUTH_ROUND_TRIPS), PSQLState.PROTOCOL_VIOLATION);
  }

  @Override
  public @Nullable Exception call() throws Exception {
    return run();
  }
}
