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
	
	public final NetworkInterface networkInterface;
	public final StandardProtocolFamily protocol;
	public final long initialDelay;
	public final long period;
	public final TimeUnit unit;
	public final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);
	
	private final NetworkExecutor nexecutor;
	private final BiConsumer<Boolean,SocketException> statusListener;
	private final Consumer<Status> consumer;
	private final ScheduledFuture<?> future;
	private final boolean linkLocal;
	
	
	boolean isUp = false;
	
	@Deprecated
	public NetworkMonitor(NetworkExecutor executor, BiConsumer<Boolean,SocketException> statusListener) throws SocketException {
		
		this(executor, statusListener, null, 1, 5);
	}
	
	@Deprecated
	public NetworkMonitor(NetworkExecutor executor, BiConsumer<Boolean,SocketException> statusListener, 
		NetworkInterface networkInterface, long initialDelay, long period) throws SocketException {
		
		this(executor, statusListener, networkInterface, INET, initialDelay, period, TimeUnit.SECONDS, false);
	}
	
	@Deprecated
	public NetworkMonitor(NetworkExecutor executor, BiConsumer<Boolean,SocketException> statusListener, 
		NetworkInterface networkInterface, StandardProtocolFamily protocol, long initialDelay, 
		long period, TimeUnit unit, boolean linkLocal) throws SocketException {
		
		this.nexecutor = executor;
		this.statusListener = statusListener;
		this.networkInterface = networkInterface == null ? defaultNetworkInterface() : networkInterface;
		this.protocol = protocol == null ? INET : protocol;
		this.initialDelay = initialDelay;
		this.period = period;
		this.unit = unit;
		this.linkLocal = linkLocal;
		isUp = this.networkInterface.isUp();
		consumer = null;
		future = executor.scheduleAtFixedRate(() -> {
				try {
					if (isUp != networkInterface.isUp()) {
						isUp = networkInterface.isUp();	
						statusListener.accept(isUp, null);
					}
				} catch (SocketException e) {
					statusListener.accept(null, e);
				}
			}, initialDelay, period, unit);
		statusListener.accept(isUp, null);
	}
	
	
	public NetworkMonitor(NetworkExecutor executor, Consumer<Status> consumer) throws SocketException {
		
		this(executor, consumer, defaultNetworkInterface(), 100, 5000);
	}
	
	public NetworkMonitor(NetworkExecutor executor, Consumer<Status> consumer, 
		NetworkInterface networkInterface, long initialDelay, long period) {
		
		this(executor, consumer, networkInterface, INET, initialDelay, period, TimeUnit.MILLISECONDS, false);
	}
	
	public NetworkMonitor(NetworkExecutor executor, Consumer<Status> consumer, 
		NetworkInterface networkInterface, StandardProtocolFamily protocol, long initialDelay, 
		long period, TimeUnit unit, boolean linkLocal) {
		
		this.nexecutor = executor;
		this.consumer = consumer;
		this.statusListener = null;
		this.networkInterface = networkInterface;
		this.protocol = protocol == null ? INET : protocol;
		this.initialDelay = initialDelay;
		this.period = period;
		this.unit = unit;
		this.linkLocal = linkLocal;
		
		future = executor.scheduleAtFixedRate(() -> consumer.accept(update()), initialDelay, period, unit);
	}
					
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
