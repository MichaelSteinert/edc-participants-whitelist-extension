package org.eclipse.edc.mvd.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.mvd.api.TransferController.PushRequest;
import java.io.InputStream;
import java.util.Map;

import java.net.URI;
import java.net.http.*;
import java.util.concurrent.CompletableFuture;

public class PushService {
    private final HttpClient http;
    private final ObjectMapper om;

    public PushService(HttpClient http, ObjectMapper om) {
        this.http = http;
        this.om = om;
    }
    public void start(String assetId, String targetUrl){
        try{
            var connectorBase = targetUrl.replace("/api/trusted-participants", "");
            var push = URI.create(connectorBase + "/api/transfers/push");

            PushRequest pr = new PushRequest(assetId, connectorBase);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(push)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(pr)))
                    .build();

            http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        }catch (IOException e){
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Overload that directly forwards an InputStream payload to the consumer instead of
     * re‑loading it from the AssetStore.
     */
    public CompletableFuture<HttpResponse<Void>> start(String assetId, String targetUrl, InputStream payload) {
        String base   = targetUrl.replace("/api/trusted-participants", "");
        String binary = base + "/api/assets/" + assetId + "/binary";

        HttpRequest post = HttpRequest.newBuilder()
                .uri(URI.create(binary))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> payload))
                .build();
        CompletableFuture<HttpResponse<Void>> fut =
                http.sendAsync(post, HttpResponse.BodyHandlers.discarding());

        createMetadata(assetId, base, binary);
        System.out.println("New asset pushed to url:" + binary);
        return fut;
    }

    // POST /management/v3/assets
    private void createMetadata(String id, String base, String binaryUrl) {
        try {
            var mgmtBase = base.replace(":9191", ":9193")
                    + "/management/v3";
            var assetJson = Map.of(
                    "@context",          Map.of("@vocab", "https://w3id.org/edc/v0.0.1/ns/"),
                    "@id",               id,
                    "@type",             "Asset",
                    "properties",        Map.of("key", "value"),
                    "privateProperties", Map.of("privateKey", "privateValue"),
                    "dataAddress",       Map.of(
                            "type",    "HttpData",
                            "baseUrl", binaryUrl)
            );

            var body = om.writeValueAsString(assetJson);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(mgmtBase + "/assets"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            http.sendAsync(req, HttpResponse.BodyHandlers.discarding());

        } catch (Exception ex) {
            throw new RuntimeException("metadata creation failed", ex);
        }
    }
}