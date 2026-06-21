package com.lokalno;

import io.grpc.*;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;

public class AuthInterceptor implements ServerInterceptor {

    // Define the metadata header key matching the Android client
    public static final Metadata.Key<String> AUTH_TOKEN_HEADER_KEY =
            Metadata.Key.of("pairing-code", Metadata.ASCII_STRING_MARSHALLER);

    // The current active pairing token stored in memory
    private final String activeToken;

    public AuthInterceptor(String activeToken) {
        this.activeToken = activeToken;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String methodName = call.getMethodDescriptor().getFullMethodName();

        // CASE 1: If it's the pre-flight verification call, we don't check headers yet.
        // We let it pass through so our service implementation can read the request body string.
        if (methodName.equalsIgnoreCase("FolderSync/VerifyToken")) {
            return next.startCall(call, headers);
        }

        // CASE 2: For the main Sync stream (or any other call), validate the Metadata header token
        String clientHeaderToken = headers.get(AUTH_TOKEN_HEADER_KEY);

        if (clientHeaderToken != null && clientHeaderToken.equals(activeToken)) {
            return next.startCall(call, headers);
        }

        // REJECT: Header token is missing or dead wrong
        Status status = Status.UNAUTHENTICATED
                .withDescription("Invalid or missing synchronization pairing token.");
        call.close(status, new Metadata());

        return new ServerCall.Listener<ReqT>() {};
    }
}
