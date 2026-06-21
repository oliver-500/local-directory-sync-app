package com.lokalno.config;

import org.aeonbits.owner.Accessible;
import org.aeonbits.owner.Config;
import org.aeonbits.owner.Config.Sources;
@Config.LoadPolicy(Config.LoadType.MERGE)
@Sources({
    "system:env",
    "file:./resources/config-${APP_ENV}.properties",
    "classpath:config-${APP_ENV}.properties",
    "file:./resources/config-local-dev-windows.properties",
    "classpath:config-local-dev-windows.properties"
})
public interface AppConfig extends Config, Accessible {
    @Key("TARGET_FOLDER_PATH")
    //@DefaultValue("/app/dataa")
    String folderPath();

    @Key("SERVER_PORT")
    @DefaultValue("50051")
    int serverPort();

    @Key("SERVER_CERT_FILE_PATH")
    @DefaultValue("./resources/certs/local-sync-server.crt")
    String serverCertFilePath();

    @Key("SERVER_KEY_FILE_PATH")
    @DefaultValue("./resources/certs/local-sync-server.key")
    String serverKeyFilePath();

    @Key("CLIENT_CA_CERT_FILE_PATH")
    @DefaultValue("./resources/certs/local-sync-ca.crt")
    String clientCaCertFilePath();
}
