package org.eclipse.edc.mvd.api;


import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.mvd.model.ServiceDescriptor;
import org.eclipse.edc.mvd.service.ServiceRegistry;

import java.util.List;
import java.util.Map;

@Path("/services")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ServiceController {


    /** Provider publishes a (new) service. */
    @POST
    public Response add(ServiceDescriptor svc){
        if(svc == null || svc.getId() == null || svc.getId().isBlank()){
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error","id missing")).build();
        }
        ServiceRegistry.add(svc);
        return Response.status(Response.Status.CREATED).build();
    }

    /** Remove service again (optional). */
    @DELETE @Path("{id}")
    public Response delete(@PathParam("id") String id){
        return ServiceRegistry.remove(id)
                ? Response.noContent().build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }


    /** List all services this connector offers. */
    @GET
    public List<ServiceDescriptor> list(){
        return ServiceRegistry.list();
    }

    /** Fetch a single descriptor. */
    @GET @Path("{id}")
    public Response get(@PathParam("id") String id){
        return ServiceRegistry.get(id)
                .map(svc -> Response.ok(svc).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }
}
