package com.maritimebank;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

import java.util.Date;
import java.util.Calendar;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

public class MtbRmiServiceImpl extends UnicastRemoteObject implements MtbRmiService {
    public boolean isExit = false;

    private String makeparam(){
		Date date = Calendar.getInstance().getTime();
		DateFormat dateFormat = new SimpleDateFormat("yyyyMMddHH");
		String strDate = dateFormat.format(date);
    	return "044525095"+strDate;
	}

	public MtbRmiServiceImpl() throws RemoteException {
		super();
	}

	@Override
	public void stop(String paramstr) throws RemoteException {
    	HASH	myHash=new HASH();
    	if(paramstr.equals(myHash.getSHA1(makeparam()))) {
			isExit = true;
		}else{
    		throw new RemoteException("Wrong!");
		}
	}

}
