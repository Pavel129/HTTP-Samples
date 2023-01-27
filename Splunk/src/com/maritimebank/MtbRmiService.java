package com.maritimebank;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface MtbRmiService extends Remote {
	void stop(String paramstr) throws RemoteException;
}
