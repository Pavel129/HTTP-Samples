package com.maritimebank;

import org.apache.log4j.Logger;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

//import javax.ws.rs.core.Response;
//import java.util.HashMap;
//import java.util.Map;
//import org.json.JSONObject;

@Path("REST")
public class RESTAPI {
    Logger logger = Logger.getLogger(this.getClass());
    //Response myResponse;

    @GET
    @Path("ReleaseDoc/{id}")
    @Produces({MediaType.TEXT_PLAIN,MediaType.APPLICATION_JSON,MediaType.APPLICATION_XML})
    public ReleaseDoc doReleaseDoc( @PathParam("id") String strUUID) {
        //return "\n This is my REST API via HTTPServer\n ID="+strUUID;
        //Map <String, String> my = new HashMap<>();
        //my.put("id",strUUID);
        ReleaseDoc rd=new ReleaseDoc();
        rd.setID(strUUID);
        logger.info("ReleaseDoc body set");
//        try {
//            myResponse=Response.status(200).entity(rd).build();
//        }catch (Exception e){
//            logger.error(e.getMessage());
//        }
        return rd;
    }


}