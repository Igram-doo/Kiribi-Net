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

import java.io.InterruptedIOException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

import rs.igram.kiribi.io.ByteStream;
import rs.igram.kiribi.io.Decoder;
import rs.igram.kiribi.io.Encodable;
import rs.igram.kiribi.io.VarInput;
import rs.igram.kiribi.io.VarInputStream;
import rs.igram.kiribi.io.VarOutput;
import rs.igram.kiribi.io.VarOutputStream;

import static rs.igram.kiribi.io.ByteUtils.extract;

import static java.util.logging.Level.*;

/**
 * Factory for endpoints.
 *
 * @author Michael Sargent
 */
public abstract class EndpointProvider<A> {
	private static final Logger LOGGER = Logger.getLogger(EndpointProvider.class.getName());
	
	final Map<Address,SocketAddress> cache = new HashMap<>();
	
	/** The executor associated with this endpoint provider. */
	public final NetworkExecutor executor;
	/** The socket address associated with this endpoint provider. */
	public final InetSocketAddress socketAddress;
	
	EndpointProvider(NetworkExecutor executor, InetSocketAddress socketAddress) {
		this.executor = executor;
		this.socketAddress = socketAddress;
	}
		
	/**
	 * Returns a udp endpoint provider.
	 *
	 * @param executor The executor the returned endpoint provider will use.	 
	 * @param socketAddress The socket address this endpoint provider will use.
	 * @param address The address the returned endpoint provider will use.
	 * @param nattAddress The socket address of the NATT server the returned endpoint provider will use.
	 * @return Returns a udp endpoint provider.
	 */
	public static EndpointProvider<ConnectionAddress> udpProvider(NetworkExecutor executor, InetSocketAddress socketAddress, Address address, SocketAddress nattAddress) {
		return new UDPEndpointProvider(executor, socketAddress, address, nattAddress);
	}
	
	/**
	 * Returns a tcp endpoint provider.
	 *
	 * @param executor The executor the returned endpoint provider will use.
	 * @param socketAddress The socket address this endpoint provider will use.
	 * @return Returns a tcp endpoint provider.
	 */
	public static EndpointProvider<SocketAddress> tcpProvider(NetworkExecutor executor, InetSocketAddress socketAddress) {
		return new TCPEndpointProvider(executor, socketAddress);
	}

	/**
	 * Returns a server endpoint.
	 *
	 * @return Returns a server endpoint.
	 * @throws IOException if there was a problem opening the server endpoint.
	 * @throws InterruptedException if the provider was interrupted while opening the server endpoint.
	 * @throws TimeoutException if the provider timed out while opening the server endpoint.
	 */
	public abstract ServerEndpoint server() 
		throws IOException, InterruptedException, TimeoutException;

	/**
	 * Returns an endpoint.
	 *
	 * @param address The address associated with the returned endpoint.
	 * @return Returns an endpoint.
	 * @throws NoRouteToHostException if this is a <code>UDPEndpointProvider</code> and the address is not 
	 * registered with the <code>NATTServer</code>.
	 * @throws IOException if there was a problem opening the endpoint.
	 * @throws InterruptedException if the provider was interrupted while opening the endpoint.
	 */
	public abstract Endpoint open(A address) 
		throws IOException, InterruptedException;
		
	/**
	 * Shuts down this endpoint provider.
	 */
	public void shutdown() {}

	// used by udp provider to multiplex services over a single address
	class MUX<S extends SecureEndpoint> {
		static final byte OPEN_SERVICE = 	 10;
		static final byte TRANSFER_SERVICE = 11; 
		static final byte CLOSE_SERVICE = 	 12;
		
		private final Map<Long,ServiceEndpoint> delegates = Collections.synchronizedMap(new HashMap<>());
		private final Consumer<Endpoint> consumer;
		
		S root;
		private Future<?> future;
		private boolean isClosed;
		private Address remote;
		
		MUX(S root, Consumer<Endpoint> consumer) {
			this.root = root;
			this.consumer = consumer;
			
			future = executor.submit(this::read);
		}

		void reset(S value){
			future.cancel(true);
			delegates.values().forEach(ServiceEndpoint::setClosed);
			delegates.clear();
			try{
				root.close();
			}catch(Exception e){
				// ignore
			}
			isClosed = false;
			root = value;
			future = executor.submit(this::read);
		}
		
		S root() {return root;}
		
		// created by client side for new outgoing session
		public ServiceEndpoint open(long address) throws IOException {
			if(isClosed) throw new IllegalStateException("MUX is closed");

			return delegates.computeIfAbsent(address, k -> new ServiceEndpoint(address));
		}

		// pass off to delegate for processing
		protected void process(long id, byte[] b) {
			if(isClosed) return;
			ServiceEndpoint delegate = delegates.get(id);
			if(delegate == null){
				delegate = new ServiceEndpoint(id);
				delegates.put(id, delegate);
				final ServiceEndpoint ep = delegate;
				executor.submit(() -> consumer.accept(ep));
			}
			delegate.queue.add(b);
		}

		protected void read() {
			while(!Thread.interrupted()){
				if(isClosed) return;
				try{
					Packet packet = root.read(Packet::new);
					switch(packet.action){
					case OPEN_SERVICE:
						break;
					case TRANSFER_SERVICE:
						process(packet.id, packet.data);
						break;
					case CLOSE_SERVICE:
						close(packet.id);
						break;
					}
				}catch(IOException e){
					return;
				}
			}
		}
		
		protected void transfer(long id, byte[] data) throws IOException {
			if(isClosed) return;
			root.write(new Packet(id, data));
		}
		
		// close delegate
		protected void close(long id) {
			if(!delegates.containsKey(id)) return;
			try{
				if(!isClosed) root.write(new Packet(id));
			}catch(IOException e){
				// ignore
			}
			ServiceEndpoint delegate = delegates.remove(id);
			if(delegate != null){
				delegate.setClosed();
			}
			// notify subclass
			onClosed(id);
		}
		
		// override as needed
		protected void onClosed(long id) {}
		
		protected void write(long id, byte[] data) throws IOException {
			if(isClosed) return;
			root.write(new Packet(id, data));
		}
		
		public synchronized void dispose(boolean notify) {
			delegates.entrySet().forEach(e -> {
				try{
					root.write(new Packet(e.getKey()));
				}catch(IOException ex){
					// ignore
				}
			});
			delegates.clear();
			// give some time to notify remote peers
			try{
				TimeUnit.MILLISECONDS.sleep(250);
			}catch(Exception z){
				// ignore
			}
			
			try{
				if(notify) root.writeRaw(new byte[]{SecureEndpoint.CLOSE});
				future.cancel(true);
				root.close();
			}catch(Exception e){
				// ignore
			}
			
			isClosed = true;
			
			// notify subclass
			onDisposed(this);
		}
			
		// override as needed
		protected void onDisposed(MUX mux) {}
		
		boolean isClosed() {return isClosed;}
		
		class ServiceEndpoint implements Endpoint {
			final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
			final long id;
			private Consumer<ConnectionState> consumer;
			private boolean isOpen = true;
			
			ServiceEndpoint(long id) {
				this(id, null);
			}
			
			ServiceEndpoint(long id, Consumer<ConnectionState> consumer) {
				this.id = id;
				this.consumer = consumer;
			}
			
			@Override
			public void state(Consumer<ConnectionState> consumer) {
				this.consumer = consumer;
			}
			
			@Override
			public void write(Encodable data) throws IOException {
				if(!isOpen) throw new IOException("Endpoint not open");
				
				MUX.this.transfer(id, data.encode());
			}

			@Override
			public <T> T read(Decoder<T> decoder) throws IOException {
				if(!isOpen) throw new IOException("Endpoint not open");
				
				try{
					return decoder.decode(queue.take());
				}catch(InterruptedException e){
					return null;
				}
			}
			
			@Override
			public boolean isOpen() {
				return isOpen;
			}

			@Override
			public void close() throws IOException {
				isOpen = false;
				MUX.this.close(id);
			}
			
			private void setClosed() {
				if(consumer != null){
					executor.submit(() -> consumer.accept(ConnectionState.CLOSED));
				}
			}
		}
		
		class Packet implements Encodable {
			final byte action;
			final long id;
			final byte[] data;
		
			Packet(long id, byte[] data) {
				action = TRANSFER_SERVICE;
				this.id = id;
				this.data = data;
			}
			
			Packet(long id) {
				action = CLOSE_SERVICE;
				this.id = id;
				this.data = new byte[0];
			}
			
			public Packet(VarInput in) throws IOException {
				action = in.readByte();
				id = in.readLong();
				data = in.readBytes();
			}
			
			@Override
			public void write(VarOutput out) throws IOException {
				out.writeByte(action);
				out.writeLong(id);
				out.writeBytes(data);
			}
		}
	}
}
