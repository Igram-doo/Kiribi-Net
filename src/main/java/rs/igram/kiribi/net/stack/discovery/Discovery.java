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
 
package rs.igram.kiribi.net.stack.discovery;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.channels.AsynchronousCloseException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import rs.igram.kiribi.io.ByteUtils;
import rs.igram.kiribi.net.Address;
import rs.igram.kiribi.net.NetworkExecutor;

import static java.util.logging.Level.*;

/**
 * 
 *
 * @author Michael Sargent
 */
public final class Discovery {
	private static final Logger LOGGER = Logger.getLogger(Discovery.class.getName());
	
	private final Map<Address, InetSocketAddress> map = new HashMap<>();
	private final InetSocketAddress group;
	private final ByteBuffer out;
	private final ByteBuffer in = ByteBuffer.allocate(40);
	
	private NetworkExecutor executor;
	private DatagramChannel channel;
    private boolean started = false;
	private Future<?> reader;
	private Future<?> broadcaster;
	private MembershipKey key;
	
	private final Object stateLock = new Object(){};
	    
	@Deprecated
	public Discovery(NetworkExecutor executor, Address address, InetSocketAddress socketAddress, InetSocketAddress group) {
		//this.executor = executor;
		this.group = group;
		
		byte[] buf = new byte[40];
		byte[] encoded = address.bytes();
		System.arraycopy(encoded, 0, buf, 0, 20);
		ByteUtils.inet(buf, 20, socketAddress);
		out = ByteBuffer.wrap(buf);
	}
	    
	public Discovery(Address address, InetSocketAddress socketAddress, InetSocketAddress group) {
		this.group = group;
		
		byte[] buf = new byte[40];
		byte[] encoded = address.bytes();
		System.arraycopy(encoded, 0, buf, 0, 20);
		ByteUtils.inet(buf, 20, socketAddress);
		out = ByteBuffer.wrap(buf);
	}
	
	public void start() throws IOException {
		synchronized(stateLock) {
			if (!started) {
				executor = new NetworkExecutor();
				// ick - fix
				MulticastSocket msock = new MulticastSocket();
				NetworkInterface ifc = msock.getNetworkInterface();
				msock.close();
				channel = DatagramChannel.open(StandardProtocolFamily.INET)
					.setOption(StandardSocketOptions.SO_REUSEADDR, true)
					.bind(new InetSocketAddress(group.getPort()))
					.setOption(StandardSocketOptions.IP_MULTICAST_IF, ifc);
				key = channel.join(group.getAddress(), ifc);
				
				reader = executor.submit(this::read);
				broadcast();
				started = true;
			}
		}		
	}
	
	public void remove(Address address) {
		synchronized(map) {
			map.remove(address);
		}
	}
	
	public InetSocketAddress address(Address address) {
		InetSocketAddress remote = null;
		synchronized(map) {
			remote = map.get(address);
		}
		
		LOGGER.log(FINE, "ADDRESS: {0} {1}", new Object[]{address, remote});
		return remote;
	}
	
	public void shutdown() {
		synchronized(stateLock) {
			if (started) {
				if (reader != null) reader.cancel(false);
				if (broadcaster != null) broadcaster.cancel(false);
				try{
					channel.close();
				} catch(IOException e) {
					// ignore
				}
				//executor.shutdown();
			}
		}
	}
	
	private void read() {
		while(!Thread.currentThread().isInterrupted() && channel.isOpen()) {
			if (key.isValid()) {
				try {
					in.clear();
					InetSocketAddress remoteAddress = (InetSocketAddress) channel.receive(in);
					in.flip();

					byte[] buf = in.array();
					Address address = new Address(ByteUtils.crop(buf, 20));
					InetSocketAddress socketAddress = (InetSocketAddress)ByteUtils.inet(buf, 20);
					
					executor.submit(() -> {
						boolean exists = false;
						synchronized(map) {
							exists = map.containsKey(address);
							map.put(address, socketAddress);
						}
				
						if (!exists) {
							LOGGER.log(FINE, "RECEIVE: {0} {1} {2}", new Object[]{remoteAddress, address, socketAddress});
							broadcast();
						}
					});
				} catch(AsynchronousCloseException e) {
					// ignore
				} catch(IOException e) {
					// log and ignore
					LOGGER.log(FINE, e.toString(), e);
				}
			}
		}
	}
	
	private void broadcast() {
		synchronized(this) {
			if (broadcaster == null) {
				broadcaster = executor.submit(() -> {
					for(int i = 0; i < 3; i++) {
						if (!channel.isOpen()) break;
						try { 
							channel.send(out, group);
							out.flip();
							Thread.sleep(60);
						} catch(IOException e) {
							// log and ignore
							LOGGER.log(FINE, e.toString(), e);
						} catch(InterruptedException e) {
							// ignore
							break;
						}
					}
					synchronized(Discovery.this) {
						broadcaster = null;
					}
				});
			}
		}		
	}
}