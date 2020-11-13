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
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import rs.igram.kiribi.net.NetworkExecutor;
import rs.igram.kiribi.crypto.Address;
import rs.igram.kiribi.net.NetworkMonitor;

import static rs.igram.kiribi.net.stack.NetworkProtocol.*;
import static rs.igram.kiribi.io.ByteUtils.*;

/**
 * 
 *
 * @author Michael Sargent
 */
public class NetworkMux {	
	protected final Map<Byte,Processor> processors = new HashMap<>();
	// offsets
	protected static int OFF_ID	= 1;
	// standard MTU - ip/udp headers
	protected static final int PACKET_SIZE = 1472;
	// linux - has the smallest default max buffer size
	// to change on linux: sysctl -w net.core.rmem_max=26214400
	protected static final int MAX_UDP_BUF_SIZE = 131071;
	
	protected NetworkExecutor executor;
	protected DatagramSocket socket;
	protected Future<?> reader;
	
	public NetworkMux(NetworkExecutor executor) {
		this.executor = executor;
	}
	
	protected InetSocketAddress start(int port) {
		executor.onShutdown(6, this::shutdown);
		InetAddress address = NetworkMonitor.inet();
		if(address == null) return null;
		InetSocketAddress inet = null;
		try{
			inet = new InetSocketAddress(address, port);
			socket = new DatagramSocket(inet);
			socket.setReceiveBufferSize(MAX_UDP_BUF_SIZE);
			socket.setSendBufferSize(MAX_UDP_BUF_SIZE);
			
			reader = submit(this::read);
		}catch(SocketException e){
			e.printStackTrace();
			// shouldn't happen
			throw new RuntimeException(e);
		}
		
		processors.values().stream().forEach(Processor::start);
		
		return inet;
	}

	public void register(Processor... processors) {
		for(Processor processor : processors) register(processor);
	}
		
	public void register(Processor processor) {
		processors.put(processor.protocol, processor);
		processor.configure(this);
	}
	
	protected void process(DatagramPacket p) {
		Processor processor = processors.get(protocol(p.getData()));
		if(processor != null) processor.process(p);
	}
	
	protected void read() {
		while(!Thread.currentThread().isInterrupted()){
			byte[] buf = new byte[PACKET_SIZE];
			DatagramPacket p = new DatagramPacket(buf, PACKET_SIZE);
			try{
				socket.receive(p);
				process(p);
			}catch(SocketException e){
				break;
			}catch(IOException e){
				// ignore - nothing we can do
			}
		}
	}
	
	public void write(DatagramPacket p) throws IOException {
		socket.send(p);
	}

	public void write(byte[] buf, SocketAddress address) throws IOException {
		socket.send(new DatagramPacket(buf, buf.length, address));
	}
	
	public static void protocol(byte[] b, byte protocol) {
		b[0] = protocol;
	}
	
	public static void address(byte[] b, Address address, int OFF_DATA) {
		System.arraycopy(address.encodeUnchecked(), 0, b, OFF_DATA, 20);
	}
	
	public static void inet(byte[] b, SocketAddress address, int OFF_DATA) {
		InetSocketAddress inet = ((InetSocketAddress)address);
		if(inet.getAddress() instanceof Inet6Address){
			System.arraycopy(inet.getAddress().getAddress(), 0, b, OFF_DATA, 16);
		}else{
			b[OFF_DATA] = (byte)0xff;
			b[OFF_DATA + 1] = (byte)0xff;
			System.arraycopy(inet.getAddress().getAddress(), 0, b, OFF_DATA + 2, 4);
			for(int i = OFF_DATA + 6; i < OFF_DATA + 16; i++) b[i] = 0;
		}
		put(b, OFF_DATA + 16, inet.getPort());
	}
	
	public static byte protocol(byte[] b) {
		return b[0];
	}
	
	public static Address address(byte[] b, int OFF_DATA) {
		return new Address(extract(b, OFF_DATA, 20));
	}
	
	public static SocketAddress inet(byte[] src, int OFF_DATA) throws UnknownHostException {
		byte[] inet = null;
		if(src[OFF_DATA] == (byte)0xff && src[OFF_DATA + 1] == (byte)0xff){
			inet = extract(src, OFF_DATA + 2, 4);
		}else{
			inet = extract(src, OFF_DATA, 16);
		}
		InetAddress add = InetAddress.getByAddress(inet);
		int port = getInt(src, OFF_DATA + 16);
		return new InetSocketAddress(add, port);
	}
	
	public final <T> Future<T> submit(Callable<T> task) {
		return executor.submit(task);
	}

	public final Future<?> submit(Runnable task) {
		return executor.submit(task);
	}

	protected void shutdown() {
		processors.values().stream().forEach(Processor::shutdown);
		try{
			if(reader != null) reader.cancel(true);
			if(socket != null) socket.close();
		}catch(Throwable e) {}
	}
}
