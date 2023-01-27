package com.maritimebank;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import oracle.ucp.UniversalConnectionPoolException;
import oracle.ucp.admin.UniversalConnectionPoolManager;
import oracle.ucp.admin.UniversalConnectionPoolManagerImpl;
import oracle.ucp.UniversalConnectionPoolAdapter;
import oracle.ucp.jdbc.PoolDataSourceFactory;
import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.ValidConnection;

import org.apache.log4j.Logger;

import java.util.Properties;

public class dbConn {
    static PoolDataSource                   pds;
    static UniversalConnectionPoolManager   mgr;
    static String                           mgrPoolName="mgr_pool";
    Logger logger = Logger.getLogger(this.getClass());

    public void terminate(){
        try {
            mgr.purgeConnectionPool(mgrPoolName);
            mgr.stopConnectionPool(mgrPoolName);
            mgr.destroyConnectionPool(mgrPoolName);
        }catch (Exception e){
            logger.error(e.getMessage());
        }
    }

    public void init(Properties propDB, Properties propCP){
        try {
            mgr = UniversalConnectionPoolManagerImpl.getUniversalConnectionPoolManager();
            //Create pool-enabled data source instance.
            pds = PoolDataSourceFactory.getPoolDataSource();
            pds.setConnectionPoolName(mgrPoolName);
            //set the connection properties on the data source.
            pds.setConnectionFactoryClassName("oracle.jdbc.pool.OracleDataSource");
            if (propDB.getProperty("service").length()>0){
                pds.setURL("jdbc:oracle:thin:@//"+propDB.getProperty("host")+":"+propDB.getProperty("port")+"/"+propDB.getProperty("service"));
            }else{
                if (propDB.getProperty("instance").length()>0)
                    pds.setURL("jdbc:oracle:thin:@" + propDB.getProperty("host") + ":" + propDB.getProperty("port") + "/" + propDB.getProperty("instance"));
            }
            pds.setUser(propDB.getProperty("username"));
            pds.setPassword(propDB.getProperty("password"));
            //Override any pool properties.
            pds.setInitialPoolSize(Integer.parseInt(propCP.getProperty("InitialPoolSize")));
            pds.setMaxPoolSize(Integer.parseInt(propCP.getProperty("MaxPoolSize")));
            pds.setValidateConnectionOnBorrow(propCP.getProperty("ValidateConnectionOnBorrow").equals("true"));
            pds.setSQLForValidateConnection(propCP.getProperty("SQLForValidateConnection"));

            mgr.createConnectionPool((UniversalConnectionPoolAdapter)pds);
            mgr.startConnectionPool(mgrPoolName);

        } catch (Exception e) {
            logger.error(e.getMessage());
        }
    }

    public Connection getConnection() throws SQLException {
        Connection ct=pds.getConnection();
        if(!((ValidConnection) ct).isValid()){
            try {
                mgr.recycleConnectionPool(mgrPoolName);
                logger.info("mgr_pool_recycled");
            }catch (UniversalConnectionPoolException rce){
                logger.error("Recycle error: "+rce.getMessage());
                try{
                    logger.info("ReStart pool");
                    mgr.stopConnectionPool(mgrPoolName);
                    mgr.startConnectionPool(mgrPoolName);
                }catch (UniversalConnectionPoolException rse){
                    logger.error("ReStart pool error: "+rse.getMessage());
                }
            }
            ct=pds.getConnection();
        }
        return ct;
    }

    public void freeConnection(Connection conn) {
        try {
            //Close the Connection.
            conn.close();
            //conn = null;
            //System.out.println("Connection returned to the UniversalConnectionPool\n");
        } catch (SQLException e) {
            logger.error(e.getMessage());
        }
    }

    public void test(Connection conn)    {
        try {
            //Connection conn = this.getConnection();
            //do some work with the connection.
            if(((ValidConnection) conn).isValid()){
                logger.info("con valid");
                Statement stmt = conn.createStatement();
                stmt.execute("select 'isValid connection check' from dual");
                logger.info("Query done");
            }else {
                logger.info("conn not valid");
            }
            //Thread.sleep(5000);
            //freeConnection(conn);
        } catch (SQLException e) {
            logger.error(e.getMessage());
        }
    }
}