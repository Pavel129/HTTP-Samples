package com.maritimebank;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.client.urlconnection.HTTPSProperties;
import org.apache.log4j.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class RESTClientJSON {

    Logger logger = Logger.getLogger(this.getClass());

    private static final String BaseURI = "https://10.11.2.102:8089/services/wh-analytics/payments/af/legal";

    SSLContext ctx=null;
    TrustAllHostNameVerifier hostnameVerifier=new TrustAllHostNameVerifier();
    // Create a trust manager that does not validate certificate chains
    TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
        public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
    }
    };
    ClientConfig config = new DefaultClientConfig();

    private void defaultConfig(){
        try {
            ctx = SSLContext.getInstance("SSL");
        }catch (NoSuchAlgorithmException e){
            logger.error(e.getMessage());
        }
        if (ctx!=null){
            try {
                ctx.init(null, trustAllCerts, null);
            } catch (KeyManagementException e) {
                logger.error(e.getMessage());
            }
            config.getProperties().put(HTTPSProperties.PROPERTY_HTTPS_PROPERTIES, new HTTPSProperties(hostnameVerifier, ctx));
        }
    }

    public String callREST(String strUrl, String strPayload){
        logger.debug("url: "+strUrl);
        logger.debug("payload: "+strPayload);
        defaultConfig();
        Client client = Client.create(config);
        WebResource webResource = client.resource(strUrl);
        ClientResponse response = webResource.type("application/json").post(ClientResponse.class, strPayload);
        String strResp=response.getEntity(String.class);
        if (response.getStatus() != 200) {
            logger.info("Failed : HTTP error code : " + response.getStatus());
            logger.info("Error: " + strResp);
        }else {
            logger.debug("REST answer:"+strResp);
        }
        return strResp;
    }

    public void test()  {
        defaultConfig();
        Client client = Client.create(config);
        WebResource webResource = client.resource(BaseURI);
        // Data send to web service.
        Date date = Calendar.getInstance().getTime();
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        String strDate = dateFormat.format(date);
        String input = "{\"dt\":\""+ strDate +"\"}";
        logger.info(input);

        ClientResponse response = webResource.type("application/json").post(ClientResponse.class, input);

        if (response.getStatus() != 200) {
            logger.info("Failed : HTTP error code : " + response.getStatus());

            String error = response.getEntity(String.class);
            logger.info("Error: " + error);
            //return;
        }else {
            logger.info("Output from Server .... \n");
            String output = response.getEntity(String.class);
            logger.info(output);
        }
    }
}
