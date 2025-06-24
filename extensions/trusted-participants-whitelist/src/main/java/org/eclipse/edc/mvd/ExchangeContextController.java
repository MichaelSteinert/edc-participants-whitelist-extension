package org.eclipse.edc.mvd;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.mvd.context.ExchangeContext;
import java.util.Map;

@Path("/context")
@Produces(MediaType.APPLICATION_JSON)
public class ExchangeContextController {
    @GET
    @Path("{assetId}")
    public Response get(@PathParam("assetId") String assetId) {
        try {
            return Response.ok(Map.of(
                            "provider", ExchangeContext.provider(assetId),
                            "consumer", ExchangeContext.consumer(assetId)))
                    .build();
        } catch (IllegalStateException ex) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", ex.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("{assetId}")
    public Response put(@PathParam("assetId") String assetId,
                        Map<String, String> body) {
        var provider = body.get("provider");
        var consumer = body.get("consumer");
        if (provider == null || consumer == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "provider / consumer missing"))
                    .build();
        }
        ExchangeContext.put(assetId, provider, consumer);
        return Response.noContent().build();
    }
}