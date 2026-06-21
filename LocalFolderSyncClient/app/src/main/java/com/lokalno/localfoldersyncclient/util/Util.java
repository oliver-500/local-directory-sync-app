package com.lokalno.localfoldersyncclient.util;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;


import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.security.Security;

public class Util {
    public static SSLSocketFactory getSslSocketFactory(
            InputStream caCertInputStream,
            InputStream clientCertAndKeyP12Stream,
            String clientPassword) throws Exception {
        Security.removeProvider("BC"); // Remove the restricted Android version
        Security.addProvider(new BouncyCastleProvider());

        // 1. Load CA Certificate to trust the server
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate caCert = cf.generateCertificate(caCertInputStream);
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("local_folder_sync_ca", caCert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        // 2. Load Client Certificate and Private Key (typically a .p12 or .pfx file)
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());

        if (clientCertAndKeyP12Stream != null) {
            // 1. Initialize and load the BouncyCastle PKCS12 KeyStore only if the stream exists
            KeyStore clientKeyStore = KeyStore.getInstance("PKCS12", "BC");
            clientKeyStore.load(clientCertAndKeyP12Stream, clientPassword.toCharArray());
            kmf.init(clientKeyStore, clientPassword.toCharArray());
        } else {
            kmf.init(null, null);
        }

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

        return sslContext.getSocketFactory();
    }




}
