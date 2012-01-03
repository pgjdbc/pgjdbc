package org.postgresql.ssl.jdbc4;

import java.io.Console;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Iterator;
import java.util.Properties;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.x500.X500Principal;

import org.postgresql.ssl.NonValidatingFactory.NonValidatingTM;
import org.postgresql.ssl.WrappedFactory;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

/**
 * Provide an SSLSocketFactory that is compatible with the libpq
 * behaviour.
 */
public class LibPQFactory extends WrappedFactory implements HostnameVerifier {

    LazyKeyManager km = null;
    String sslmode; 
    /**
     * 
     * @param info the connection parameters
     * The following parameters are used:
     * sslmode,sslcert,sslkey,sslrootcert,sslhostnameverifier,sslpasswordcallback,sslpassword
     * @throws GeneralSecurityException
     * @throws IOException 
     * @throws UnsupportedCallbackException 
     */
    public LibPQFactory(Properties info) throws PSQLException {
      try
      {
        sslmode = info.getProperty("sslmode");
        SSLContext ctx = SSLContext.getInstance("TLS"); // or "SSL" ?
        
        //Determinig the default file location
        String pathsep = System.getProperty("path.separator");
        String defaultdir;
        boolean defaultfile = false;
        if (System.getProperty("os.name").toLowerCase().indexOf("windows") > -1)
        { //It is Windows
          defaultdir = System.getenv("APPDATA")+pathsep+"postgresql"+pathsep;
        } else {
          defaultdir = System.getProperty("user.home")+pathsep+".postgresql"+pathsep;
        }
        
        //Load the client's certificate and key
        String sslcertfile = info.getProperty("sslcert");
        if (sslcertfile == null)
        { //Fall back to default
          defaultfile = true;
          sslcertfile = defaultdir + "postgresql.crt";
        }
        String sslkeyfile = info.getProperty("sslkey");
        if (sslkeyfile == null)
        { //Fall back to default
          defaultfile = true;
          sslkeyfile = defaultdir + "postgresql.pk8";
        }
        
        //Determine the callback handler
        CallbackHandler cbh;
        String sslpasswordcallback = info.getProperty("sslpasswordcallback");
        if (sslpasswordcallback != null)
        {
          try
          {
            cbh = (CallbackHandler)AbstractJdbc4MakeSSL.instantiate(sslpasswordcallback, info, false, null);
          }
          catch (Exception e)
          {
              throw new PSQLException(GT.tr("The password callback class provided {0} could not be instantiated.", sslpasswordcallback), PSQLState.CONNECTION_FAILURE, e);
          }
        } else {
          cbh = new ConsoleCallbackHandler(info.getProperty("sslpassword"));
        }
         
        //If the properies are empty, give null to prevent client key selection
        km = new LazyKeyManager(("".equals(sslcertfile) ? null : sslcertfile), ("".equals(sslkeyfile) ? null : sslkeyfile), cbh, defaultfile);
        
        TrustManager[] tm;    
        if("verify-ca".equals(sslmode) || "verify-full".equals(sslmode))
        {
          //Load the server certificate
          
          TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
          KeyStore ks;
          try
          {
            ks = KeyStore.getInstance("jks");
          } catch (KeyStoreException e)
          {
            throw new NoSuchAlgorithmException("jks KeyStore not available"); //this should never happen
          }
          String sslrootcertfile = info.getProperty("sslrootcert");
          if (sslrootcertfile == null)
          { //Fall back to default
            sslrootcertfile = defaultdir + "root.crt";
          }
          FileInputStream fis;
          try
          {
            fis = new FileInputStream(sslrootcertfile);
          } catch (FileNotFoundException ex)
          {
            throw new PSQLException(GT.tr("Could not open SSL root certificate file {0}.", new Object[]{sslrootcertfile}), PSQLState.CONNECTION_FAILURE, ex);
          }
          try
          {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            //Certificate[] certs = cf.generateCertificates(fis).toArray(new Certificate[]{}); //Does not work in java 1.4
            Object[] certs = cf.generateCertificates(fis).toArray(new Certificate[]{});
            fis.close();
            ks.load(null, null);
            for(int i=0; i<certs.length; i++)
            {
              ks.setCertificateEntry("cert"+i, (Certificate)certs[i]);
            }
            tmf.init(ks);
          }
          catch (IOException ioex)
          {
            throw new PSQLException(GT.tr("Could not read SSL root certificate file {0}.", new Object[]{sslrootcertfile}), PSQLState.CONNECTION_FAILURE, ioex);
          } catch (GeneralSecurityException gsex)
          {
            throw new PSQLException(GT.tr("Loading the SSL root certificate {0} into a TrustManager failed.", new Object[]{sslrootcertfile}), PSQLState.CONNECTION_FAILURE, gsex);
          } 
          tm = tmf.getTrustManagers();
        } else { //server validation is not required
          tm = new TrustManager[] { new NonValidatingTM() };
        }

        //finally we can initialize the context
        try
        {
          ctx.init(new KeyManager[]{km}, tm, null);
        } catch (KeyManagementException ex)
        {
          throw new PSQLException(GT.tr("Could not initialize SSL context.", null), PSQLState.CONNECTION_FAILURE, ex);
        }

        _factory = ctx.getSocketFactory();
      }
      catch(NoSuchAlgorithmException ex)
      {
        throw new PSQLException(GT.tr("Could not find a java cryptographic algorithm: {0}.", new Object[]{ex.getMessage()}), PSQLState.CONNECTION_FAILURE, ex);
      }
    }

    /**
     * Propagates any exception from LazyKeyManager
     * @throws PSQLException
     */
    public void throwKeyManagerException() throws PSQLException
    {
      if (km!=null) km.throwKeyManagerException();
    }
    
    /**
     * A CallbackHandler that reads the password from the console
     * or returns the password given to it's constructor.
     *
     */
    class ConsoleCallbackHandler implements CallbackHandler {

      private char[] password = null;
      
      public ConsoleCallbackHandler(String password)
      {
        if (password != null) this.password = password.toCharArray();
      }
      
      /**
       * Handles the callbacks.
       * @param callbacks The callbacks to handle
       * @throws UnsupportedCallbackException If the console is not available
       * or other than PasswordCallback is supplied
       */
      public void handle(Callback[] callbacks) throws IOException,
          UnsupportedCallbackException {
        Console cons = System.console();
        if (cons==null && password==null) {throw new UnsupportedCallbackException(callbacks[0], "Console is not available");}
        for (int i=0; i<callbacks.length; i++)
        {
          if (callbacks[i] instanceof PasswordCallback)
          {
            if (password==null)
            {
              //It is used instead of cons.readPassword(prompt), because the prompt may contain '%' characters
              ((PasswordCallback)callbacks[i]).setPassword(cons.readPassword("%s", new Object[]{((PasswordCallback)callbacks[i]).getPrompt()}));
            } else {
              ((PasswordCallback)callbacks[i]).setPassword(password);
            }
          } else {
            throw new UnsupportedCallbackException(callbacks[i]);
          }
      }
      
    }
  }

    /**
     * Verifies the server certificate according to the libpq rules.
     * The cn attribute of the certificate is matched against the hostname.
     * If the cn  attribute starts with an asterisk (*), it will be treated as a wildcard,
     * and will match all characters except a dot (.). This means the certificate
     * will not match subdomains. If the connection is made using an IP address instead of a
     * hostname, the IP address will be matched (without doing any DNS lookups).
     * @param hostname Hostname or IP address of the server.
     * @param session The  SSL session.
     * @returns true if the certificate belongs to the server, false otherwise.
     */
    public boolean verify(String hostname, SSLSession session) {
      X509Certificate[] peerCerts;
      try
      {
        peerCerts = (X509Certificate[])session.getPeerCertificates();
      } catch (SSLPeerUnverifiedException e)
      {
        return false;
      }
      if (peerCerts == null || peerCerts.length == 0) {
        return false;
      }
      //Extract the common name
      X509Certificate serverCert = peerCerts[0];
      LdapName DN;
      try
      {
        DN = new LdapName(serverCert.getSubjectX500Principal().getName(X500Principal.RFC2253));
      } catch (InvalidNameException e)
      {
        return false; 
      }
      String CN = null;
      Iterator it = DN.getRdns().iterator();
      //for(Rdn rdn : DN.getRdns())
      while(it.hasNext())
      {
        Rdn rdn = (Rdn)it.next();
        if ("CN".equals(rdn.getType())) //Multiple AVAs are not treated
        {
          CN = (String)rdn.getValue();
          break;
        }
      }
      if (CN == null)
      {
        return false;
      } else if (CN.startsWith("*"))
      { //We have a wildcard
        if (hostname.endsWith(CN.substring(1)))
        { //Avoid IndexOutOfBoundsException because hostname already ends with CN
          return !(hostname.substring(0, hostname.length()-CN.length()+1).contains("."));
        } else {
          return false;
        }
      } else {
        return CN.equals(hostname);
      }
    }

}

