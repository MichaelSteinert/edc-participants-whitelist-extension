package org.eclipse.edc.mvd.service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.monitor.Monitor;

/** Downloads binary of an asset from another connector. */
public class PullService {
    private final HttpClient http;
    private final Monitor monitor;
    public PullService(HttpClient http, Monitor monitor){
        this.http = http;
        this.monitor = monitor;
    }
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public InputStream fetch(String assetId, String providerUrl) {
        try {
            // remove the “entryId::” prefix (if present) before talking to the provider
            String remoteId = assetId.contains("::")
                    ? assetId.substring(assetId.indexOf("::") + 2)
                    : assetId;

            URI prov = URI.create(providerUrl);
            String mgmtBase = prov.getScheme() + "://" + prov.getHost() + ":9193/management/v3";
            monitor.info("[PullService] providerUrl       = " + providerUrl);
            monitor.info("[PullService] managementBase    = "+ mgmtBase);
            monitor.info("[PullService] localAssetId      = " + assetId);
            monitor.info("[PullService] remoteAssetId     = " + remoteId);

            /* ── 1. fetch metadata ─────────────────────────────────────── */
            HttpRequest metaReq = HttpRequest.newBuilder()
                    .uri(URI.create(mgmtBase + "/assets/" + remoteId))
                    .GET()
                    .build();

            HttpResponse<String> metaResp = http.send(metaReq, HttpResponse.BodyHandlers.ofString());
            if (metaResp.statusCode() != 200) {
                throw new IllegalStateException("Management API replied " + metaResp.statusCode());
            }

            String sourceUrl = MAPPER.readTree(metaResp.body())
                    .path("dataAddress")
                    .path("baseUrl")
                    .asText(null);
            if (sourceUrl == null || sourceUrl.isBlank()) {
                throw new IllegalStateException("Asset " + remoteId + " has no dataAddress.baseUrl");
            }

            HttpRequest dataReq = HttpRequest.newBuilder()
                    .uri(URI.create(sourceUrl))
                    .GET()
                    .build();

            HttpResponse<InputStream> dataResp =
                    http.send(dataReq, HttpResponse.BodyHandlers.ofInputStream());

            if (dataResp.statusCode() != 200) {
                throw new IllegalStateException("Source HTTP fetch failed (" + dataResp.statusCode() + ")");
            }
            return dataResp.body();

        } catch (Exception ex) {
            monitor.severe("[PullService] ERROR: " + ex.getMessage());
            throw new RuntimeException("Failed to fetch asset " + assetId, ex);
        }
    }
}