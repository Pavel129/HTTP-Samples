import com.maritimebank.*;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.sql.*;

import java.net.MalformedURLException;

import java.rmi.RemoteException;
import java.rmi.NotBoundException;
import java.rmi.AlreadyBoundException;
import java.rmi.Naming;
import java.rmi.registry.Registry;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import oracle.jdbc.OraclePreparedStatement;
import org.apache.log4j.Logger;
import org.apache.log4j.xml.DOMConfigurator;

import java.util.Date;
import java.util.Calendar;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

public class PayCheck {

    static String   cmd_l_cmd    = "start";

    static String   mainIniName = "PayCheck.ini";
    static String   rmi_host    = "127.0.0.1";
    static int      rmi_port    = 9000;
    static int      rmi_sleep   = 1000;
    static int      main_sleep  = 1000;
    static int      exit_sleep  = 100;
    static int      rest_port   = 9001;

    static Thread   proc_thread;
    static ScheduledExecutorService rmi_check_service;
    static MtbRmiServiceImpl rmiService;
    static Registry registry;

    static Map< String, Properties> mainIniValues;
    static Properties               propDatabase;
    static Properties               propUCP;
    static Properties               propThread;
    static Properties               propREST;

    static Logger           logger          = Logger.getLogger(PayCheck.class);
    
    static dbConn           dbc             = new dbConn();
    static RESTClientJSON   rest_client     = new RESTClientJSON();
    static RESTServer       myRESTServer    = new RESTServer();

    static boolean doExit   = false;
    static boolean isActive = false;
    static boolean isInit   = true;

    private static String makeParam(){
        Date date = Calendar.getInstance().getTime();
        DateFormat dateFormat = new SimpleDateFormat("yyyyMMddHH");
        String strDate = dateFormat.format(date);
        return "044525095"+strDate;
    }

    public static void stopAgent() {
        logger.info("Stop processing");
        doExit=true;
        logger.info("stop flag set for main thread");
        rmi_check_service.shutdown();
        logger.info("rmi checker stopped");
        try {
            if (registry != null) {
                // unbind all services registered for local registry
                for (String name : registry.list()) {
                    logger.debug("unbinding {0} "+ name);
                    registry.unbind(name);
                }
                UnicastRemoteObject.unexportObject(registry, true);
                registry = null;
                logger.info("rmi registry unExported");
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
        }
        try{
            logger.info("wait for main thread exit");
            while(isActive) {
                //noinspection BusyWait
                Thread.sleep(exit_sleep);
            }
        }catch (InterruptedException e){
            logger.error(e.getMessage());
        }
        logger.info("App is Stopped call system.exit(0)");

        System.exit(0);
    }


    public static void process() {
        String strResp;
        String strURL;
        isActive=true;
        while(!doExit){
            try{
                try {
                    Connection my_con = dbc.getConnection();
                    // workload for DB
                    // dbc.test(my_con);
                    Statement qryStm = my_con.createStatement();
                    // get docs from abs
                    ResultSet rs = qryStm.executeQuery("select IPAYCHECKID, CURL, UUID, CPAYLOAD from mtb.pay_check where ISTATUS=1 and ROWNUM<=10");
                    while (rs.next()) {
                        //strResp=""
                        logger.debug("IPAYCHECKID: "+rs.getLong("IPAYCHECKID"));
                        logger.debug("UUID: "+rs.getString("UUID"));
                        // call rest for every doc
                        OraclePreparedStatement prepStm = (OraclePreparedStatement) my_con.prepareStatement("call mtb.pay_check_pkg.setStatus(:pid,:pStatus)");
                        prepStm.setLongAtName("pid",rs.getLong("IPAYCHECKID"));
                        prepStm.setIntAtName("pStatus",2);
                        prepStm.execute();
                        prepStm.close();
                        logger.debug("Saved state: 2");
                        //
                        strURL = rs.getString("CURL")+"?id="+rs.getString("UUID");
                        logger.info("URL: "+strURL);
                        //
                        strResp = rest_client.callREST(strURL, rs.getString("CPAYLOAD"));
                        logger.info("REST: "+strResp);
                        // save rest state for every doc
                        prepStm = (OraclePreparedStatement) my_con.prepareStatement("call mtb.pay_check_pkg.setResult(:pid,:pStatus)");
                        prepStm.setLongAtName("pid",rs.getLong("IPAYCHECKID"));
                        prepStm.setStringAtName("pStatus",strResp);
                        prepStm.execute();
                        prepStm.close();
                        logger.debug("Saved result");
                    }
                    qryStm.close();
                    //
                    dbc.freeConnection(my_con);
                }catch (SQLException e){
                    logger.error("SQL: "+e.getMessage());
                }catch(Exception e){
                    logger.error(e.getMessage());
                }
                //noinspection BusyWait
                Thread.sleep(main_sleep);
            }catch(InterruptedException e){
                logger.error(e.getMessage());
            }
        }
        dbc.terminate();
        isActive=false;
        logger.info("doExit");
    }

    private static void initLogging() {
        try {
            String log4j = System.getProperty("log4j.configuration");
            URL urlLogConf = new URL(log4j);
            //PropertyConfigurator.configureAndWatch(url.getFile(), 5000);
            File log4jConfigurationFile = new File(urlLogConf.toURI());
            if (log4jConfigurationFile.exists()) {
                DOMConfigurator.configureAndWatch(
                        log4jConfigurationFile.getAbsolutePath(), 5000);
            }
        } catch (MalformedURLException | URISyntaxException e) {
            logger.warn("Системная настройка log4j.configuration имеет некорректный вид. Автоматическое обновление параметров логирования отключено.");
            logger.warn(e.getMessage());
        }

    }

    public static void main(String[] args)throws RemoteException,AlreadyBoundException,NotBoundException,MalformedURLException {

        if (args.length > 0) {
            cmd_l_cmd = args[0];
        }
        initLogging();
        
        isInit=loadParams(mainIniName);

        if (isInit) {

            if (cmd_l_cmd.equals("start")) {
                logger.info("Start");

                dbc.init(propDatabase,propUCP);

                registry = LocateRegistry.createRegistry(rmi_port);
                rmiService = new MtbRmiServiceImpl();
                registry.bind("rmiService", rmiService);

                //Поток для мониторинга таблицы АБС
                proc_thread = new Thread(PayCheck::process);
                proc_thread.start();

                //Поток для проверки сигнала на выход
                Runnable my_runnable = () -> {
                    if (rmiService.isExit) {
                        stopAgent();
                    }
                };
                //Мониторинг флага isExit каждую секунду
                rmi_check_service = Executors.newScheduledThreadPool(1);
                rmi_check_service.scheduleAtFixedRate(my_runnable, 0, rmi_sleep, TimeUnit.MILLISECONDS);

                // start http
                try {
                    propREST.setProperty("resource","com.maritimebank");
                    myRESTServer.start(propREST);
                }catch (IOException e){
                    logger.error(e.getMessage());
                }

            } else if (cmd_l_cmd.equals("stop")) {
                HASH myHash = new HASH();
                logger.info("Send Stop command");
                MtbRmiService rmiService = (MtbRmiService) Naming.lookup("rmi://" + rmi_host + ":" + rmi_port + "/rmiService");
                rmiService.stop(myHash.getSHA1(makeParam()));
                logger.info("Send Stop done");
            }
        }else{
            logger.error("Can't load INI");
        }
    }

    private static boolean loadParams(String iniName){
        // load ini
        try{
            //mainIniReader   = new iniReader();
            mainIniValues = iniReader.parseINI(iniName);
            if (mainIniValues.containsKey("DATABASE")) {
                propDatabase = mainIniValues.get("DATABASE");
                if (propDatabase.containsKey("host")) {
                    if (propDatabase.getProperty("host").length()==0){
                        logger.error("INI file: Не указан host для сервера СУБД");
                        return false;
                    }
                } else {
                    logger.error("INI file: ключ host секции DATABASE не найден");
                    return false;
                }
                if (propDatabase.containsKey("port")) {
                    if (propDatabase.getProperty("port").length()==0){
                        logger.error("INI file: Не указан port для сервера СУБД");
                        return false;
                    }else{
                        try {
                            if (Integer.parseInt(propDatabase.getProperty("port"))<=0){
                                logger.error("INI file: Неверно указан port для сервера СУБД");
                                return false;
                            }
                        }catch(NumberFormatException e) {
                            logger.error("INI file: ошибка чтения секции [DATABASE] ключ port: " + e.getMessage());
                            return false;
                        }
                    }
                } else {
                    logger.error("INI file: ключ port секции [DATABASE] не найден");
                    return false;
                }

                if (!propDatabase.containsKey("instance")) {
                    logger.error("INI file: ключ instance секции [DATABASE] не найден");
                    return false;
                }

                if (!propDatabase.containsKey("service")) {
                    logger.error("INI file: ключ service секции [DATABASE] не найден");
                    return false;
                }

                if (propDatabase.getProperty("instance").length()==0
                  &&propDatabase.getProperty("service").length()==0
                   ){
                    logger.error("INI file: Не указан ни service ни instance для сервера БД");
                    return false;
                }

                if (propDatabase.containsKey("username")) {
                    if (propDatabase.getProperty("username").length()==0){
                        logger.error("INI file: Не указан username для БД");
                        return false;
                    }
                } else {
                    logger.error("INI file: ключ username секции [DATABASE] не найден");
                    return false;
                }

                if (propDatabase.containsKey("password")) {
                    if (propDatabase.getProperty("password").length()==0){
                        logger.error("INI file: Не указан password для БД");
                        return false;
                    }
                } else {
                    logger.error("INI file: ключ password секции [DATABASE] не найден");
                    return false;
                }
            } else {
                logger.error("INI file: отсутствует секция [DATABASE]");
                return false;
            }
            if (mainIniValues.containsKey("UCP")) {
                propUCP = mainIniValues.get("UCP");
                if (propUCP.containsKey("InitialPoolSize")) {
                    if (propUCP.getProperty("InitialPoolSize").length()==0){
                        logger.error("INI file: Не указан InitialPoolSize для UCP");
                        return false;
                    }else{
                        try {
                            if (Integer.parseInt(propUCP.getProperty("InitialPoolSize"))<=0){
                                logger.error("INI file: Неверно указан InitialPoolSize для UCP");
                                return false;
                            }
                        }catch(NumberFormatException e) {
                            logger.error("INI file: ошибка чтения секции [UCP] ключ InitialPoolSize: " + e.getMessage());
                            return false;
                        }
                    }
                } else {
                    logger.error("INI file: ключ InitialPoolSize секции [UCP] не найден");
                    return false;
                }
                if (propUCP.containsKey("MaxPoolSize")) {
                    if (propUCP.getProperty("MaxPoolSize").length()==0){
                        logger.error("INI file: Не указан MaxPoolSize для UCP");
                        return false;
                    }else{
                        try {
                            if (Integer.parseInt(propUCP.getProperty("MaxPoolSize"))<=0){
                                logger.error("INI file: Неверно указан MaxPoolSize для UCP");
                                return false;
                            }
                        }catch(NumberFormatException e) {
                            logger.error("INI file: ошибка чтения секции [UCP] ключ MaxPoolSize: " + e.getMessage());
                            return false;
                        }
                    }
                } else {
                    logger.error("INI file: ключ MaxPoolSize секции [UCP] не найден");
                    return false;
                }

                if (propUCP.containsKey("ValidateConnectionOnBorrow")) {
                    if (propUCP.getProperty("ValidateConnectionOnBorrow").length()==0){
                        logger.error("INI file: Не указан ValidateConnectionOnBorrow для UCP");
                        return false;
                    }
                } else {
                    logger.error("INI file: ключ ValidateConnectionOnBorrow секции [UCP] не найден");
                    return false;
                }
                if (propUCP.containsKey("SQLForValidateConnection")) {
                    if (propUCP.getProperty("SQLForValidateConnection").length()==0) {
                        logger.error("INI file: Не указан SQLForValidateConnection для UCP");
                        return false;
                    }
                } else {
                    logger.error("INI file: ключ SQLForValidateConnection секции [UCP] не найден");
                    return false;
                }
            }else{
                logger.error("INI file: отсутствует секция [UCP]");
                return false;
            }
            if (mainIniValues.containsKey("PROC")) {
                propThread = mainIniValues.get("PROC");
                if (propThread.containsKey("rmi_host")){
                    if (propThread.getProperty("rmi_host").length()>0){
                        rmi_host=propThread.getProperty("rmi_host");
                    }
                }
                if (propThread.containsKey("rmi_port")){
                    if (propThread.getProperty("rmi_port").length()>0){
                        try {
                            if (Integer.parseInt(propThread.getProperty("rmi_port"))>0){
                                rmi_port=Integer.parseInt(propThread.getProperty("rmi_port"));
                            }
                        }catch(NumberFormatException e) {
                            logger.error("INI file: ошибка чтения секции [PROC] ключ rmi_port: " + e.getMessage());
                            return false;
                        }
                    }
                }
                if (propThread.containsKey("rmi_sleep")){
                    if (propThread.getProperty("rmi_sleep").length()>0){
                        try {
                            if (Integer.parseInt(propThread.getProperty("rmi_sleep"))>0){
                                rmi_sleep=Integer.parseInt(propThread.getProperty("rmi_sleep"));
                            }
                        }catch(NumberFormatException e) {
                            logger.error("INI file: ошибка чтения секции [PROC] ключ rmi_sleep: " + e.getMessage());
                            return false;
                        }
                    }
                }
                if (propThread.containsKey("main_sleep")){
                    if (propThread.getProperty("main_sleep").length()>0){
                        try {
                            if (Integer.parseInt(propThread.getProperty("main_sleep"))>0){
                                main_sleep=Integer.parseInt(propThread.getProperty("main_sleep"));
                            }
                        }catch(NumberFormatException e) {
                            logger.error("INI file: ошибка чтения секции [PROC] ключ main_sleep: " + e.getMessage());
                            return false;
                        }
                    }
                }
                if (propThread.containsKey("exit_sleep")){
                    if (propThread.getProperty("exit_sleep").length()>0){
                        try {
                            if (Integer.parseInt(propThread.getProperty("exit_sleep"))>0){
                                exit_sleep=Integer.parseInt(propThread.getProperty("exit_sleep"));
                            }
                        }catch(NumberFormatException e) {
                            logger.error("INI file: ошибка чтения секции [PROC] ключ exit_sleep: " + e.getMessage());
                            return false;
                        }
                    }
                }
            }else{
                logger.error("INI file: отсутствует секция [PROC]");
                return false;
            }
            if (mainIniValues.containsKey("REST")) {
                propREST = mainIniValues.get("REST");
                if (propREST.containsKey("rest_port")){
                    if (propREST.getProperty("rest_port").length()>0){
                        try {
                            if (Integer.parseInt(propREST.getProperty("rest_port"))>0){
                                rest_port=Integer.parseInt(propREST.getProperty("rest_port"));
                            }
                        }catch(NumberFormatException e) {
                            logger.error("INI file: ошибка чтения секции [REST] ключ rest_port: " + e.getMessage());
                            return false;
                        }
                    }
                }
            }else{
                logger.error("INI file: отсутствует секция [REST]");
                return false;
            }
        }catch (IOException e){
            logger.error(e.getMessage());
            return false;
        }
        return true;
    }

}
