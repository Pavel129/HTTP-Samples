package com.maritimebank.mandarinagent;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletContextEvent;
import javax.servlet.annotation.WebListener;
import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import oracle.jdbc.OracleTypes;
import org.apache.hc.client5.http.ClientProtocolException;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.EntityBuilder;
import org.apache.hc.client5.http.impl.auth.CredentialsProviderBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@WebListener
public class Process implements javax.servlet.ServletContextListener{
	private static final Logger logger = LogManager.getLogger(Process.class);
	private static final Properties prop = new Properties();
	private static final ScheduledThreadPoolExecutor scheduler= (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(2);
	private static final DBConnection db_con=new DBConnection();
	private static final Queue< Map < String, Object >> queue = new LinkedList<>();
	private static final Map<String,String> calls=new HashMap<>();
	private static ScheduledFuture<?> db_process_task,http_process_task;
	
	@Override
	public void contextInitialized(final ServletContextEvent sce){
		String setupConfigFile = sce.getServletContext().getInitParameter("setup-config-location");
		String setup_filename = sce.getServletContext().getRealPath("") + File.separator + setupConfigFile;
		InputStream stream;
		try {
			stream = new FileInputStream(setup_filename);
			try {
				prop.load(stream);
			} catch (IOException e) {
				logger.error(e);
			}
		} catch (FileNotFoundException e) {
			logger.error(e);
		}
		//
		
		//
		scheduler.setRemoveOnCancelPolicy(true);
		sce.getServletContext().setAttribute("Process", this);
		logger.info("MTB start");
		
		db_process_task=scheduler.scheduleWithFixedDelay(
				new DB_Process(
						"db_process_task"
						,Integer.parseInt(prop.getProperty("DBParallelRequests","1"))
						,prop.getProperty("card_vendor")
				)
				,0
				,Integer.parseInt(prop.getProperty("DBScanInterval","1000"))
				, TimeUnit.MILLISECONDS);
		http_process_task=scheduler.scheduleWithFixedDelay(
				new HTTP_Sender("http_process_task", prop)
				,0
				,Integer.parseInt(prop.getProperty("HTTPSendInterval","1000"))
				,TimeUnit.MILLISECONDS);
	}
	
	@Override
	public final void contextDestroyed(final ServletContextEvent sce) {
		logger.info("MTB finish begin");
		db_process_task.cancel(true);
		http_process_task.cancel(true);
		scheduler.shutdown();
		
		try {
			// Wait a while for existing tasks to terminate
			if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
				scheduler.shutdownNow(); // Cancel currently executing tasks
				// Wait a while for tasks to respond to being cancelled
				if (!scheduler.awaitTermination(5, TimeUnit.SECONDS))
					System.err.println("executorService did not terminate");
			}
		} catch (InterruptedException ie) {
			// (Re-)Cancel if current thread also interrupted
			scheduler.shutdownNow();
			// Preserve interrupt status
			Thread.currentThread().interrupt();
		}
		
		// Now deregister JDBC drivers in this context's ClassLoader:
		// Get the webapp's ClassLoader
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		// Loop through all drivers
		Enumeration<Driver> drivers = DriverManager.getDrivers();
		while (drivers.hasMoreElements()) {
			Driver driver = drivers.nextElement();
			if (driver.getClass().getClassLoader() == cl) {
				// This driver was registered by the webapp's ClassLoader, so deregister it:
				try {
					logger.info("De registering JDBC driver {}", driver);
					DriverManager.deregisterDriver(driver);
				} catch (SQLException ex) {
					logger.error("Error de registering JDBC driver {}", driver, ex);
					Thread.currentThread().interrupt();
				}
			} else {
				// driver was not registered by the webapp's ClassLoader and may be in use elsewhere
				logger.trace("Not de registering JDBC driver {} as it does not belong to this webapp's ClassLoader", driver);
			}
		}
		try { Thread.sleep(5000L); } catch (Exception e) {
			logger.error(e.getMessage());
			Thread.currentThread().interrupt();
		} // Use this thread sleep
		logger.info("MTB finish done");
	}
	
	public void set_callback(String content){
		calls.put("text",content);
		Connection save_msg = db_con.getConnection();
		CallableStatement stmSave=null;
		try {
			stmSave = save_msg.prepareCall("begin :ret := mtb.card_pay.set_msg(pVendor=>:pVendor,pMsg=>:pMsg); end;");
			stmSave.registerOutParameter("ret",OracleTypes.INTEGER);
			stmSave.setString("pVendor",prop.getProperty("card_vendor"));
			stmSave.setString("pMsg",content);
			stmSave.execute();
			logger.info("Processing save_msg DBResult:"+stmSave.getLong("ret"));
			//
			stmSave.close();
			db_con.freeConnection(save_msg);
		}catch (Exception e){
			logger.error(e.getMessage());
			if(!(stmSave==null)){
				try{
					stmSave.close();
				}catch (Exception e1){
					logger.error(e1.getMessage());
				}
			}
			db_con.freeConnection(save_msg);
		}
	}
	public String get_call(){
		
		return calls.size()>0?calls.get("text"):"";
	}
	
	static class DBConnection{
		private final Logger logger = LogManager.getLogger(DBConnection.class);
		public Connection getConnection(){
			Context ctx;
			Connection con = null;
			try {
				ctx = new InitialContext();
				DataSource ds = (DataSource)ctx.lookup("java:/comp/env/jdbc/abs_database");
				con = ds.getConnection();
			} catch (NamingException | SQLException e) {
				logger.error(e.getMessage());
			}
			return con;
		}
		
		public void freeConnection(Connection con){
			try {
				con.close();
			} catch (SQLException e) {
				logger.error(e.getMessage());
			}
		}
	}
	
	static class DB_Process implements Runnable {
		
		private final Logger logger=LogManager.getLogger(this.getClass());
		String tn;
		final int             parallel_count;
		final String          vendor;
		
		public DB_Process( String tn, int par_count, String vendor_name) {
			this.tn=tn;
			this.parallel_count=par_count;
			this.vendor=vendor_name;
			logger.debug(this.getClass().getName()+" constructed");
		}
		
		@Override
		public void run() {
			Thread.currentThread().setName(tn);
			//logger.info("MTB 1");
			Connection list_con = db_con.getConnection();
			CallableStatement stmList=null;
			Map<String,Object> queue_map;
			try {
				stmList = list_con.prepareCall("begin :ret := mtb.card_pay.get_requests(pCount=>:pCount, pVendor=>:pVendor); end;");
				stmList.registerOutParameter("ret", OracleTypes.CURSOR);
				stmList.setInt("pCount",parallel_count);
				stmList.setString("pVendor",vendor);
				stmList.execute();
				ResultSet rs = (ResultSet)stmList.getObject("ret");
				long rID=0;
				String rStatus="1";
				while (rs.next()) {
					logger.debug("db_request record got");
					try {
						rID = rs.getLong("ICPID");
						logger.info("ID:"+rID+" Function: " + rs.getString("CCPACTION"));
						queue_map = new HashMap<>();
						queue_map.put("ICPID", rID);
						queue_map.put("CCPURL", rs.getString("CCPURL"));
						queue_map.put("CCPAUTHKEY", rs.getString("CCPAUTHKEY"));
						queue_map.put("CCPAUTHVALUE", rs.getString("CCPAUTHVALUE"));
						queue_map.put("CCPREQUEST", rs.getString("CCPREQUEST"));
						queue_map.put("CCPMETHOD", rs.getString("CCPMETHOD"));
						queue.offer(queue_map);
					}catch(Exception e){
						logger.error("ID:"+rID+" : "+e.getMessage());
						rStatus="E";
					}
					if(!DB_save_status(rID,rStatus)){
						logger.error("ID:"+rID+" Не удалось сохранить статус для:"+rID);
					}
				}
				//logger.debug("no more new messages");
				rs.close();
				stmList.close();
				db_con.freeConnection(list_con);
			} catch (SQLException e) {
				logger.error(e.getMessage());
				
				if(!(stmList==null)){
					try{
						logger.trace(stmList.toString());
						stmList.close();
					}catch (Exception e1){
						logger.error(e1.getMessage());
					}
				}
				db_con.freeConnection(list_con);
			}
		}
	}
	
	static boolean DB_save_status(Long rID, String status){
		Connection save_status_con = db_con.getConnection();
		CallableStatement stmSaveStatus=null;
		try {
			stmSaveStatus = save_status_con.prepareCall("begin :ret := mtb.card_pay.set_status(pCPID=>:pCPID,pStatus=>:pStatus); end;");
			stmSaveStatus.registerOutParameter("ret",OracleTypes.INTEGER);
			stmSaveStatus.setLong("pCPID",rID);
			stmSaveStatus.setString("pStatus",status);
			stmSaveStatus.execute();
			logger.info("ID:"+rID+" Processing set_status pID:"+rID+" DBResult:"+stmSaveStatus.getLong("ret"));
			//
			stmSaveStatus.close();
			db_con.freeConnection(save_status_con);
			return true;
		}catch (Exception e){
			logger.error("ID:"+rID+" : "+e.getMessage());
			if(!(stmSaveStatus==null)){
				try{
					stmSaveStatus.close();
				}catch (Exception e1){
					logger.error(e1.getMessage());
				}
			}
			db_con.freeConnection(save_status_con);
		}
		return false;
	}
	
	static boolean DB_save_result(Long rID, String result){
		Connection save_con = db_con.getConnection();
		CallableStatement stmSave=null;
		try {
			stmSave = save_con.prepareCall("begin :ret := mtb.card_pay.set_response(pCPID=>:pGCPID,pResponse=>:pResponse); end;");
			stmSave.registerOutParameter("ret",OracleTypes.INTEGER);
			stmSave.setLong("pCPID",rID);
			stmSave.setString("pResponse",result);
			stmSave.execute();
			logger.info("ID:"+rID+" Processing save_result pID:"+rID+" DBResult:"+stmSave.getLong("ret"));
			//
			stmSave.close();
			db_con.freeConnection(save_con);
			return true;
		}catch (Exception e){
			logger.error("ID:"+rID+" : "+e.getMessage());
			if(!(stmSave==null)){
				try{
					stmSave.close();
				}catch (Exception e1){
					logger.error(e1.getMessage());
				}
			}
			db_con.freeConnection(save_con);
		}
		return false;
	}
	
	static class HTTP_Sender implements Runnable {
		private final Logger logger=LogManager.getLogger(this.getClass());
		String request,tn, rURL, rAuthKey, rAuthValue, rMethod;
		long rID;
		Properties prop;
		
		public HTTP_Sender(String p_tn, Properties p_prop) {
			this.tn=p_tn;
			this.prop=p_prop;
			logger.debug(this.getClass().getName()+" constructed");
		}
		
		@Override
		public void run() {
			Thread.currentThread().setName(tn);
			//logger.info("MTB 2");
			String result=null ;
			
			if (queue.size()>0){
				Map<String,Object> rr=queue.remove();
				
				this.rID        =(long)rr.get("ICPID");
				this.request    =(String)rr.get("CCPREQUEST");
				this.rURL       =(String)rr.get("CCPURL");
				this.rAuthKey   =(String)rr.get("CCPAUTHKEY");
				this.rAuthValue =(String)rr.get("CCPAUTHVALUE");
				this.rMethod    =(String)rr.get("CCPMETHOD");
				
				logger.debug("ID:"+rID+" HTTP_Sender: "+rID);
				logger.trace("ID:"+rID+" HTTP_Sender content: "+request);
				
				HttpPost    http_post=null;
				HttpGet     http_get=null;
				CloseableHttpClient http_client;
				
				RequestConfig request_config = RequestConfig
						.custom()
						.setConnectionRequestTimeout(30000, TimeUnit.MILLISECONDS)
						.build();
						
				if(   prop.getProperty("proxy_use","no").equalsIgnoreCase("yes")
					&&!prop.getProperty("proxy_host","proxy_host").equalsIgnoreCase("proxy_host")
					&&!prop.getProperty("proxy_port","proxy_port").equalsIgnoreCase("proxy_port")
				){
					if(   !prop.getProperty("proxy_user","proxy_user").equalsIgnoreCase("proxy_user")
						&&!prop.getProperty("proxy_pass","proxy_pass").equalsIgnoreCase("proxy_pass")
					){
						http_client= HttpClients
								.custom()
								.setProxy(new HttpHost("http"
										,prop.getProperty("proxy_host")
										,Integer.parseInt(prop.getProperty("proxy_port"))))
								.setDefaultCredentialsProvider(CredentialsProviderBuilder.create()
										.add(new AuthScope(
												prop.getProperty("proxy_host")
												,Integer.parseInt(prop.getProperty("proxy_port"))
												)
											,prop.getProperty("proxy_user")
											,prop.getProperty("proxy_pass").toCharArray()
										)
										.build())
								.build();
					}else{
						http_client= HttpClients
								.custom()
								.setProxy(new HttpHost("http"
												,prop.getProperty("proxy_host")
												,Integer.parseInt(prop.getProperty("proxy_port")))
								)
								.build();
					}
				}else{
					http_client= HttpClients.createDefault();
				}
				
				if (rMethod.equalsIgnoreCase("POST")) {
					try {
						http_post = new HttpPost(this.rURL);
						http_post.setConfig(request_config);
						http_post.setEntity(EntityBuilder.create().setText(request).setContentType(ContentType.APPLICATION_JSON).build());
						if (rAuthKey != null && rAuthValue != null && rAuthKey.length() > 0 && rAuthValue.length() > 0) {
							http_post.setHeader(rAuthKey, rAuthValue);
						}
						logger.trace("ID:"+rID+" Request:\n"+http_post);
					} catch (Exception e) {
						logger.error("ID:"+rID+" Can't create Request");
						logger.error("ID:"+rID+" : "+e.getMessage());
					}
				}
				
				if (rMethod.equalsIgnoreCase("GET")) {
					try {
						http_get = new HttpGet(this.rURL);
						http_get.setConfig(request_config);
						if (request!=null&&request.length()>0){
							http_get.setEntity(EntityBuilder.create().setText(request).setContentType(ContentType.APPLICATION_JSON).build());
						}
						if (rAuthKey != null && rAuthValue != null && rAuthKey.length() > 0 && rAuthValue.length() > 0) {
							http_get.setHeader(rAuthKey, rAuthValue);
						}
						logger.trace("ID:"+rID+" Request:\n"+http_get);
					} catch (Exception e) {
						logger.error("ID:"+rID+" Can't create Request");
						logger.error("ID:"+rID+" : "+e.getMessage());
					}
				}
				
				logger.debug("ID:"+rID+" Pre send");
				
				final HttpClientResponseHandler<String> responseHandler = response -> {
					final int status = response.getCode();
					//if (status >= HttpStatus.SC_SUCCESS && status < HttpStatus.SC_REDIRECTION) {
					
					logger.debug("ID:"+rID+" status: "+ status +" "+ response.getReasonPhrase());
					
					final HttpEntity entity = response.getEntity();
					try {
						return entity != null ? EntityUtils.toString(entity, StandardCharsets.UTF_8) : null;
					} catch (final ParseException ex) {
						throw new ClientProtocolException(ex);
					}
				};
				
				try {
					if(rMethod.equalsIgnoreCase("POST")) {
						final String responseBody = http_client.execute(http_post, responseHandler);
						logger.trace("ID:"+rID+"\n"+responseBody);
						result = responseBody;
					}
					if(rMethod.equalsIgnoreCase("GET")) {
						final String responseBody = http_client.execute(http_get, responseHandler);
						logger.trace("ID:"+rID+"\n"+responseBody);
						result = responseBody;
					}
				} catch (Exception e) {
					logger.error("ID:"+rID+" : "+e.getMessage());
				}
			}
			// save result
			if(result!=null&&result.length()>0){
				if(!DB_save_result(rID, result)){
					logger.error("ID:"+rID+" Не удалось записать результат в БД.");
				}
			}
		}
	}
	
	public String getState(){
		StringBuilder result = new StringBuilder("Task info:");
		result.append("\nTotal : ").append(scheduler.getTaskCount());
		result.append("\nActive: ").append(scheduler.getActiveCount());
		result.append("\nQueue : ").append(scheduler.getQueue().size());
		result.append("\nProperties:");
		prop.stringPropertyNames().stream().sorted().forEach(
				k -> {
					if (k.toLowerCase().contains("pass")){
						result.append("\n\t").append(k).append(": ").append("******");
					}else{
						result.append("\n\t").append(k).append(": ").append(prop.getProperty(k));
					}
				}
		);
		
		for (Runnable qe : scheduler.getQueue() ) {
			result.append("\n").append(qe.toString());
		}
		
		result.append("\nReq queue size: ").append(queue.size());
		
		return result.toString();
	}
	
	public String get_status_interval(){
		return prop.getProperty("status_refresh_seconds","5");
	}
}
