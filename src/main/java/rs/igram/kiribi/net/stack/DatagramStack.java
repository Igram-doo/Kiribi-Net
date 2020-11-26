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
 
package rs.igram.kiribi.net.stack;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnknownHostException;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

import rs.igram.kiribi.crypto.Address;
import rs.igram.kiribi.net.NetworkExecutor;
import rs.igram.kiribi.net.NetworkMonitor;
import rs.igram.kiribi.net.stack.kap.KAPProcessor;
import rs.igram.kiribi.net.stack.rmp.RMPProcessor;

/**
 * 
 *
 * @author Michael Sargent
 */
public abstract class DatagramStack {
	public static enum Mode {IPV4, IPV6, DUAL};
	
	protected final StandardProtocolFamily protocol;
	protected final NetworkMux mux;
	protected final Address address;
	protected final SocketAddress serverAddress;
	protected final int port;
	protected final BiConsumer<SocketAddress,byte[]> consumer;
	protected KAPProcessor kap;
	protected RMPProcessor rmp;
		
	protected DatagramStack(NetworkExecutor executor, Address address, SocketAddress serverAddress, StandardProtocolFamily protocol, int port, BiConsumer<SocketAddress,byte[]> consumer) {	
//		this.key = key;
		this.address = address;
		this.serverAddress = serverAddress;
		this.protocol = protocol;
		this.port = port;
		this.consumer = consumer;
		
		mux = new NetworkMux(executor);
	}
	
	public final StandardProtocolFamily protocol() {return protocol;}
	
	public void configure() {}
	
	public void start() {
		InetSocketAddress inet = mux.start(port);
//		if(inet != null) register(inet);
// TODO!!!
	/*			
				NetworkMonitor.onInetChanged(inet -> {	
					// network monitor might set inet to null on disconnect
					// and dhcp could assign same address on reconnect ?
					// don't want to register twice on reconnect
					if(inet == null) return;
					register();
				});
	*/			

	}
	
	public void shutdown() {
		mux.shutdown();
	}
	
	public Future<Boolean> send(SocketAddress address, byte[] data) throws InterruptedException {	
		return rmp.send(address, data);
	}
	
	public void process(DatagramPacket p) {
		rmp.process(p);
	}

	public void register() throws IOException {
		register(address, new InetSocketAddress(NetworkMonitor.inet(), port));
	}

	public abstract SocketAddress connect(Address address) throws IOException;
	
	protected abstract void register(Address address, InetSocketAddress inet) throws IOException;
}