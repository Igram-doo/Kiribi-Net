/* 
 * MIT License
 * 
 * Copyright (c) 2020 Igram, d.o.o.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
 
package rs.igram.kiribi.net;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.UnknownHostException;
import java.util.Enumeration;                    
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;

import static java.net.StandardProtocolFamily.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.logging.Level.*;

/**
 * Singleton providing information and methods related to the network.
 *
 * @author Michael Sargent
 */
public class NetworkMonitor {
	private static final Logger LOGGER = Logger.getLogger(NetworkMonitor.class.getName());
	
	private static ScheduledFuture<?> monitor;
	private static InetAddress inet;
	private static final CopyOnWriteArrayList<Consumer<InetAddress>> consumers = new CopyOnWriteArrayList();
	private static NetworkExecutor executor;
	
	private final NetworkExecutor nexecutor;
	private final BiConsumer<Boolean,SocketException> statusListener;
	private final NetworkInterface networkInterface;
	private final long initialDelay;
	private final long period;
	private final ScheduledFuture<?> future;
	boolean isUp = false;
	
	//NetworkMonitor() {}
	
	public NetworkMonitor(NetworkExecutor executor, BiConsumer<Boolean,SocketException> statusListener) throws SocketException {
		
		this(executor, statusListener, null, 1, 5);
	}
	
	public NetworkMonitor(NetworkExecutor executor, BiConsumer<Boolean,SocketException> statusListener, 
		NetworkInterface networkInterface, long initialDelay, long period) throws SocketException {
		
		this.nexecutor = executor;
		this.statusListener = statusListener;
		this.networkInterface = networkInterface == null ? defaultNetworkInterface() : networkInterface;
		this.initialDelay = initialDelay;
		this.period = period;
		isUp = this.networkInterface.isUp();
		
		future = executor.scheduleAtFixedRate(() -> {
				try {
					if (isUp != networkInterface.isUp()) {
						isUp = networkInterface.isUp();	
						statusListener.accept(isUp, null);
					}
				} catch (SocketException e) {
					statusListener.accept(null, e);
				}
			}, initialDelay, period, SECONDS);
		statusListener.accept(isUp, null);
	}
	
	public void terminate() {
		if(future != null) future.cancel(true);
	}
	
	public static NetworkInterface defaultNetworkInterface() throws SocketException {
		for(Enumeration<NetworkInterface> n = NetworkInterface.getNetworkInterfaces(); n.hasMoreElements();){
			NetworkInterface i = n.nextElement();
			if(!i.isLoopback() && !i.isVirtual() && !i.toString().contains("Teredo")){
				for(Enumeration<InetAddress> e = i.getInetAddresses(); e.hasMoreElements();){
					InetAddress a = e.nextElement();
					if(a instanceof Inet4Address && !a.isLinkLocalAddress()) return i;
				}
			}
		}
		return null;	
	}
	
	/**
	 * Returns the inet address address of the system.
	 *
	 * @return Returns the inet address address of the system.
	 */
	public static InetAddress inet() {
		try {
			return inet(defaultNetworkInterface());	
		} catch (SocketException e) {
			return null;
		}
	}
	
	public static InetAddress inet(NetworkInterface iface) {
		return inet(iface, INET, false);	
	}
	
	public static InetAddress inet(NetworkInterface iface, StandardProtocolFamily protocol, boolean linkLocal) {
		for(Enumeration<InetAddress> e = iface.getInetAddresses(); e.hasMoreElements();){
			InetAddress a = e.nextElement();
			switch(protocol){
			case INET: 
				if(a instanceof Inet4Address && linkLocal == a.isLinkLocalAddress()) return a;
				break;
			case INET6: 
				if(a instanceof Inet6Address && linkLocal == a.isLinkLocalAddress()) return a;
				break;
			}
		}
		return null;	
	}
	
	/**
	 * This method starts the network monitor.
	 *
	 * @param executor The executor this monitor will use.
	 */
	@Deprecated
	public static void monitor(NetworkExecutor executor) {
		synchronized(NetworkMonitor.class){
			if(monitor != null) return;
			NetworkMonitor.executor = executor;
			
			monitor = executor.scheduleAtFixedRate(NetworkMonitor::update, 0, 5, SECONDS);
		}
	}

	/**
	 * Returns the inet address address of the system.
	 *
	 * @return Returns the inet address address of the system.
	 */
//	public static InetAddress inet() {return inet;}
	
	static void addConsumer(Consumer<InetAddress> consumer) {consumers.addIfAbsent(consumer);}
	
	static void removeConsumer(Consumer<InetAddress> consumer) {consumers.remove(consumer);}
	
	private static void consume(InetAddress item, boolean async) {
		if(async){
			executor.submit(() -> consumers.forEach(c -> c.accept(item)));
		}else{
			consumers.forEach(c -> c.accept(item));
		}
	}
	
	private static Consumer<InetAddress> consumeIf(Predicate<InetAddress> predicate, Consumer<InetAddress> consumer) {
		return t -> {
			if(predicate.test(t)) consumer.accept(t);
		};
	}

	/**
	 * This method will run the provided runnable when the network becomes available.
	 *
	 * @param action The runnable to execute when the network becomes available.
	 */
	@Deprecated
	public static void onAvailable(Runnable action) {
		synchronized (consumers) {
			if(inet != null) executor.submit(action);
			addConsumer(i -> {
				if(i != null) executor.submit(action);
			});
		}
	}

	private static InetAddress address() {
		try{
			return inet(defaultNetworkInterface(), INET, false);
		} catch (SocketException e) {
			return null;
		}
	}
/*
	static InetAddress inet(StandardProtocolFamily protocol, boolean linkLocal) {
		InetAddress inet = null;
		try{
			for(Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements();){
				NetworkInterface i = e.nextElement();
				if(!i.isLoopback() && i.isUp() && !i.isVirtual() && !i.toString().contains("Teredo")){
					inet = inet(i, protocol, linkLocal);
					if(inet != null) return inet;
				}
			}
		}catch(SocketException e){
			LOGGER.log(SEVERE, e.toString(), e);
		}catch(Throwable e){
			LOGGER.log(SEVERE, e.toString(), e);
		}
		
		return inet;
	}
*/	
	/**
	 * Returns <code>true</code> if the network is available, <code>false</code> otherwise.
	 *
	 * @return Returns the inet address address of the system.
	 */
	@Deprecated
	public static boolean isUp() {
		return inet != null;
	}
	
	private static void update() {
		final InetAddress old = inet;
		inet = address();
		if(inet != old) consume(inet, false);
	}
	
	private static void shutdown() {
		if(monitor != null) monitor.cancel(true);
	}
}
