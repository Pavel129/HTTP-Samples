package com.maritimebank;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Properties;

import javax.ws.rs.core.UriBuilder;

import com.sun.jersey.api.container.httpserver.HttpServerFactory;
import com.sun.jersey.api.core.PackagesResourceConfig;
import com.sun.jersey.api.core.ResourceConfig;
import com.sun.net.httpserver.HttpServer;

public class RESTServer {

    Logger logger = Logger.getLogger(this.getClass());

    static String   classResource;
    static int      rest_port;

    public void start(Properties pREST) throws IOException {
        logger.info("http start...");
        classResource=pREST.getProperty("resource");
        rest_port=Integer.parseInt(pREST.getProperty("rest_port"));
        //System.out.println("Starting  Embedded Jersey HTTPServer...\n");
        HttpServer RESTHTTPServer = createRESTHttpServer();
        RESTHTTPServer.start();

        //System.out.println("Started Embedded Jersey HTTPServer Successfully !!!");
        logger.info("http started");
    }

    private static HttpServer createRESTHttpServer() throws IOException {
        ResourceConfig RESTResourceConfig = new PackagesResourceConfig(classResource);
        return HttpServerFactory.create(getRESTURI(), RESTResourceConfig);
    }

    private static URI getRESTURI() {
        return UriBuilder.fromUri("http://" + RESTGetHostName() + "/").port(rest_port).build();
    }

    private static String RESTGetHostName() {
        String hostName = "localhost";
        try {
            hostName = InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        return hostName;
    }
}

