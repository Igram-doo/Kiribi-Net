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

import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.UnknownHostException;
import java.util.Enumeration;   
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static java.net.StandardProtocolFamily.*;

/**
 * Singleton providing information and methods related to the network.
 *
 * @author Michael Sargent
 */
public class NetworkMonitor {
	/** The <code>NetworkInterface</code> to monitor. */
	public final NetworkInterface networkInterface;
	/** The <code>StandardProtocolFamily</code> to monitor. */
	public final StandardProtocolFamily protocol;
	/** The initial delay to wait before monitoring. */
	public final long initialDelay;
	/** The period between monitoring updates. */
	public final long period;
	/** The <code>TimeUnit</code> of the initialValue and period paramenters. */
	public final TimeUnit unit;
	/** An <code>AtomicReference</code> holding the status of this monitor. */
	public final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);
	
	private final Consumer<Status> consumer;
	private final ScheduledFuture<?> future;
	private final boolean linkLocal;
	
	public NetworkMonitor(NetworkExecutor executor, Consumer<Status> consumer) throws SocketException {
		this(executor, consumer, defaultNetworkInterface(), 100, 5000);
	}
	
	public NetworkMonitor(NetworkExecutor executor, Consumer<Status> consumer, 
		NetworkInterface networkInterface) {
		
		this(executor, consumer, networkInterface, INET, 100, 5000, TimeUnit.MILLISECONDS, false);
	}
	
	public NetworkMonitor(NetworkExecutor executor, Consumer<Status> consumer, 
		NetworkInterface networkInterface, long initialDelay, long period) {
		
		this(executor, consumer, networkInterface, INET, initialDelay, period, TimeUnit.MILLISECONDS, false);
	}
	
	public NetworkMonitor(NetworkExecutor executor, Consumer<Status> consumer, 
		NetworkInterface networkInterface, StandardProtocolFamily protocol, long initialDelay, 
		long period, TimeUnit unit, boolean linkLocal) {
		
		this.consumer = consumer;
		this.networkInterface = networkInterface;
		this.protocol = protocol == null ? INET : protocol;
		this.initialDelay = initialDelay;
		this.period = period;
		this.unit = unit;
		this.linkLocal = linkLocal;
		
		future = executor.scheduleAtFixedRate(() -> consumer.accept(update()), initialDelay, period, unit);
	}
					
	/**
	 * Returns the inet address of the associated with this network monitor.
	 *
	 * @return The inet address of the associated with this network monitor.
	 */
	public InetAddress inetAddress() {
		for(Enumeration<InetAddress> e = networkInterface.getInetAddresses(); e.hasMoreElements();){
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
	 * Terminates this network monitor.
	 */
	public void terminate() {
		if(future != null) future.cancel(true);
	}
	
	private Status update() {
		Status tmp = null;
		try {
			tmp = networkInterface.isUp() ? Status.UP : Status.DOWN;
		} catch (SocketException e) {
			tmp = Status.ERROR;
		}
		status.set(tmp);
		return tmp;
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
	 * Returns the default inet address of the system.
	 *
	 * @return Returns the inet address of the system.
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
	
	public static enum Status {PENDING, UP, DOWN, ERROR}
}
