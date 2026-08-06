package com.lokalno.localfoldersyncclient.util;

import android.net.Uri;
import android.os.Build;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;


import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.security.Security;

public class Util {

    public static String getHumanReadablePath(Uri uri) {
        if (uri == null) return "";

        String path = uri.getPath();
        if (path == null) return uri.toString();

        // 1. Decode URL characters (e.g., %20 becomes space, %2F becomes /)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            path = URLDecoder.decode(path, StandardCharsets.UTF_8);
        }

        // 2. Strip away SAF prefixes
        if (path.contains("/tree/")) {
            path = path.substring(path.indexOf("/tree/") + 6);
        } else if (path.contains("/document/")) {
            path = path.substring(path.indexOf("/document/") + 10);
        }

        // 3. Handle common storage types
        if (path.startsWith("primary:")) {
            path = path.replace("primary:", "Internal Storage > ");
        } else if (path.startsWith("raw:")) {
            path = path.replace("raw:", "");
        } else if (path.contains(":")) {
            // Handles SD Cards or specific volume names (e.g., "1A2B-3C4D:Folder")
            path = path.replace(":", " > ");
        }

        // 4. Clean up remaining slashes for a polished UI appearance
        path = path.replaceAll("/", " > ");

        // Remove trailing or leading " > " if they exist
        if (path.startsWith(" > ")) path = path.substring(3);
        if (path.endsWith(" > ")) path = path.substring(0, path.length() - 3);

        return path;
    }
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
