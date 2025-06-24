package org.eclipse.edc.mvd.api;

import java.io.InputStream;
import java.io.IOException;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.edc.mvd.storage.AssetStore;

@Path("/assets")
@Produces("application/octet-stream")
public class AssetBinaryController {
    private final AssetStore store;
    @Inject
    public AssetBinaryController(AssetStore store) {
        this.store = store;
    }

    @POST @Path("/{id}/binary")
    @Consumes("application/octet-stream")
    public Response upload(@PathParam("id") String id, java.io.InputStream in) throws IOException{
        store.save(id, in);
        return Response.status(Response.Status.CREATED).build();
    }

    @GET @Path("/{id}/binary")
    public Response download(@PathParam("id") String id) throws IOException{
        if(!store.exists(id)){
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(store.load(id), MediaType.APPLICATION_JSON).build();
    }
}