package com.jivesoftware.os.miru.reader.deployable;

import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 *
 */
@Singleton
@Path("/")
public class MiruReaderUIEndpoints {

    private final MiruReaderUIService service;

    public MiruReaderUIEndpoints(@Context MiruReaderUIService service) {
        this.service = service;
    }

    @GET
    @Path("/")
    @Produces(MediaType.TEXT_HTML)
    public Response get() {
        String rendered = service.render();
        return Response.ok(rendered).build();
    }

    @GET
    @Path("/partitions")
    @Produces(MediaType.TEXT_HTML)
    public Response getPartitions() {
        String rendered = service.renderPartitions();
        return Response.ok(rendered).build();
    }

}
