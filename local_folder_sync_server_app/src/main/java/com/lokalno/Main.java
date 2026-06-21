package com.lokalno;

import com.lokalno.config.AppConfig;
import com.lokalno.config.ConfigUtil;
import com.lokalno.util.FileSystemUtil;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.aeonbits.owner.ConfigFactory;


public class Main {

    static void main() throws Exception {
        AppConfig config = ConfigFactory.create(AppConfig.class);
        config.list(System.out);
        System.out.println("CURRENT WORKING DIRECTORY: " + System.getProperty("user.dir"));
        Path targetDirectoryPath;
        try {
            targetDirectoryPath =
                    FileSystemUtil.createTargetDirectory(config.folderPath());
        } catch (IOException e) {
            throw new RuntimeException("Could not create essentially needed server target directory.");
        }

        BlockingQueue<Path> filePaths = new LinkedBlockingQueue<>();
        new Thread(new FolderWatcher(targetDirectoryPath, path -> filePaths.offer(path))).start();

        FileSystemUtil.TlsCredentials tlsCredentials = ConfigUtil.loadTlsConfig(config);
        SslContext sslContext = GrpcSslContexts.configure(
                SslContextBuilder.forServer(tlsCredentials.cert(), tlsCredentials.key())
                        .trustManager(tlsCredentials.ca())
                        //.clientAuth(ClientAuth.REQUIRE)
        ).build();

        String pairingToken = StorageManager.getOrGeneratePairingToken();

        AuthInterceptor authInterceptor = new AuthInterceptor(pairingToken);
        FolderSyncServer.FolderSyncServerImpl service = new FolderSyncServer.FolderSyncServerImpl(filePaths, pairingToken);
        service.startMainServerWorker();

        Server server = NettyServerBuilder.forPort(config.serverPort())
                .sslContext(sslContext)
                .addService(ServerInterceptors.intercept(service, authInterceptor))
                .maxInboundMessageSize(64 * 1024 * 1024)
                .build()
                .start();
        System.out.println("Server started on port " + config.serverPort());
        System.out.println("Pairing code:" + pairingToken);

        server.awaitTermination();
    }

}