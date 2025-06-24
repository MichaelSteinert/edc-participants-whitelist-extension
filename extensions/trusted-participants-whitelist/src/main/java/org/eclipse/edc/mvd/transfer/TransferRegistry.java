package org.eclipse.edc.mvd.transfer;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class TransferRegistry {
    private static final Map<String, CompletableFuture<? extends HttpResponse<?>>> FUTS = new ConcurrentHashMap<>();
    public static void register(String id, CompletableFuture<? extends HttpResponse<?>> fut) {
        FUTS.put(id, fut);
    }
    public static String stateOf(String id) {
        var fut = FUTS.get(id);
        if (fut == null) return "UNKNOWN";
        if(!fut.isDone()) return "RUNNING";
        try {
            var r = fut.get();
            return r.statusCode() >= 200 && r.statusCode() < 300 ? "COMPLETED" : "ERROR";
        }catch (Exception e) { return "ERROR"; }
    }
}