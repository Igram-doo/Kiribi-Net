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
import java.net.InetSocketAddress;
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
 * Network monitoring class providing information and methods related to the network.
 *
 * @author Michael Sargent
 */
public class NetworkMonitor {
	/** Default <code>protocol</code>: INET. */
	public static final StandardProtocolFamily DEFAULT_PROTOCOL = INET;
	/** Default <code>initialDelay</code>: 100l. */
	public static final long DEFAULT_INITIAL_DELAY = 100l;
	/** Default <code>period</code>: 5000l. */
	public static final long DEFAULT_PERIOD = 5000l;
	/** Default <code>unit</code>: MILLISECONDS. */
	public static final TimeUnit DEFAULT_TIME_UNIT = TimeUnit.MILLISECONDS;
	
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

	/**
	 * Instantiates a new <code>NetworkMonitor</code> initialized with the given parameters.
	 *
	 * @param executor The <code>NetworkExecutor</code> to use.
	 * @param consumer The <code>Consumer</code> which will be notified of status changes.
	 * @throws SocketException if there was a problem determining the default <code>NetworkInterface</code>.
	 */		
	public NetworkMonitor(NetworkExecutor executor, Consumer<Status> consumer) throws SocketException {
		this(executor, consumer, defaultNetworkInterface(), DEFAULT_INITIAL_DELAY, DEFAULT_PERIOD);
	}

	/**
	 * Instantiates a new <code>NetworkMonitor</code> initialized with the given parameters.
	 *
	 * @param executor The <code>NetworkExecutor</code> to use.
	 * @param consumer The <code>Consumer</code> which will be notified of status changes.
	 * @param networkInterface The <code>NetworkInterface</code> to use.
	 */		
	public NetworkMonitor(NetworkExecutor executor, Consumer<Status> consumer, 
		NetworkInterface networkInterface) {
		
		this(executor, consumer, networkInterface, DEFAULT_PROTOCOL, DEFAULT_INITIAL_DELAY, DEFAULT_PERIOD, DEFAULT_TIME_UNIT, false);
	}

	/**
	 * Instantiates a new <code>NetworkMonitor</code> initialized with the given parameters.
	 *
	 * @param executor The <code>NetworkExecutor</code> to use.
	 * @param consumer The <code>Consumer</code> which will be notified of status changes.
	 * @param networkInterface The <code>NetworkInterface</code> to use.
	 * @param initialDelay The initial delay to wait before monitoring.
	 * @param period The period between monitoring updates.
	 */	
	public NetworkMonitor(NetworkExecutor executor, Consumer<Status> consumer, 
		NetworkInterface networkInterface, long initialDelay, long period) {
		
		this(executor, consumer, networkInterface, INET, initialDelay, period, DEFAULT_TIME_UNIT, false);
	}
	
	/**
	 * Instantiates a new <code>NetworkMonitor</code> initialized with the given parameters.
	 *
	 * @param executor The <code>NetworkExecutor</code> to use.
	 * @param consumer The <code>Consumer</code> which will be notified of status changes.
	 * @param networkInterface The <code>NetworkInterface</code> to use.
	 * @param protocol The <code>StandardProtocolFamily</code> to use.
	 * @param initialDelay The initial delay to wait before monitoring.
	 * @param period The period between monitoring updates.
	 * @param unit The <code>TimeUnit</code> of the initialValue and period paramenters.
	 * @param linkLocal The linkLocal flag to use.
	 */		
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
			var a = e.nextElement();
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
	 * Returns the inet socket address of the associated with this network monitor and the given port.
	 *
	 * @param port The port of the new socket address.
	 * @return The inet socket address of the associated with this network monitor and the given port.
	 */
	public InetSocketAddress socketAddress(int port) {
		InetAddress inet = inetAddress();
		return inet == null ? null : new InetSocketAddress(inet, port);
	}
	
	/**
	 * Terminates this network monitor.
	 */
	public void terminate() {
		if(future != null) {
			future.cancel(true);
			status.set(Status.TERMINATED);
		}
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
	
	/**
	 * Returns the default <code>NetworkInterface</code> of the system.
	 *
	 * @return Returns the default <code>NetworkInterface</code> of the system.
	 * @throws SocketException if there was a problem determining the default <code>NetworkInterface</code>.
	 */	
	public static NetworkInterface defaultNetworkInterface() throws SocketException {
		for(Enumeration<NetworkInterface> n = NetworkInterface.getNetworkInterfaces(); n.hasMoreElements();){
			var i = n.nextElement();
			if(!i.isLoopback() && !i.isVirtual() && !i.toString().contains("Teredo")){
				for(Enumeration<InetAddress> e = i.getInetAddresses(); e.hasMoreElements();){
					var a = e.nextElement();
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
		
	/**
	 * Returns the ipv4 inet address associated with the provided <code>NetworkInterface</code>.
	 *
	 * @param iface The <code>NetworkInterface</code> to use.
	 * @return Returns the inet address of the system.
	 */
	public static InetAddress inet(NetworkInterface iface) {
		return inet(iface, INET, false);	
	}
	
	/**
	 * Returns the default inet address of the system.
	 *
	 * @param iface The <code>NetworkInterface</code> to use.
	 * @param protocol The <code>StandardProtocolFamily</code> to use.
	 * @param linkLocal The linkLocal flag to use.
	 * @return Returns the inet address of the system.
	 */	
	public static InetAddress inet(NetworkInterface iface, StandardProtocolFamily protocol, boolean linkLocal) {
		for(Enumeration<InetAddress> e = iface.getInetAddresses(); e.hasMoreElements();){
			var a = e.nextElement();
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
	
	/** Indicates the status of a network monitor. */
	public static enum Status {
		/** Indicates the monitor has not yet determine the state of the network being monitored. */
		PENDING, 
		/** Indicates the network being monitored is up. */
		UP, 
		/** Indicates the network being monitored is down. */
		DOWN, 
		/** Indicates the monitor has encountered an error determining the state of the network being monitored. */
		ERROR, 
		/** Indicates the monitor has not been terminated. */
		TERMINATED
	}
}
