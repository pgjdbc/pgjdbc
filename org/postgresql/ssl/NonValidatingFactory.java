/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/ssl/NonValidatingFactory.java,v 1.3 2004/11/09 08:53:00 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.ssl;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.security.GeneralSecurityException;

public class NonValidatingFactory extends WrappedFactory {

    public NonValidatingFactory(String arg) throws GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLS"); // or "SSL" ?

        ctx.init(null,
                 new TrustManager[] { new NonValidatingTM() },
                 null);

        _factory = ctx.getSocketFactory();
    }

    class NonValidatingTM implements X509TrustManager {

        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        public void checkClientTrusted(X509Certificate[] certs, String authType) {
        }

        public void checkServerTrusted(X509Certificate[] certs, String authType) {
        }
    }

}

