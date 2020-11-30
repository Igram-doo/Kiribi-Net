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
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import rs.igram.kiribi.crypto.Address;
import rs.igram.kiribi.crypto.Challenge;
//import rs.igram.kiribi.crypto.Key;
import rs.igram.kiribi.crypto.KeyExchange;
//import rs.igram.kiribi.crypto.SignedData;
import rs.igram.kiribi.io.ByteStream;
import rs.igram.kiribi.io.Decoder;
import rs.igram.kiribi.io.Encodable;
import rs.igram.kiribi.io.VarInput;
import rs.igram.kiribi.io.VarInputStream;
import rs.igram.kiribi.io.VarOutput;
import rs.igram.kiribi.io.VarOutputStream;

import static rs.igram.kiribi.io.ByteUtils.extract;

/**
 * Factory for endpoints.
 *
 * @author Michael Sargent
 */
public abstract class EndpointProvider<A> {
	final Map<Address,SocketAddress> cache = new HashMap<>();
	final NetworkExecutor executor;
	
	EndpointProvider(NetworkExecutor executor) {
		this.executor = executor;
	}
		
	/**
	 * Returns a udp endpoint provider.
	 *
	 * @param executor The executor the returned endpoint provider will use.
	 * @param address The address the returned endpoint provider will use.
	 * @param socketAddress The socket address of the NATT server the returned endpoint provider will use.
	 * @return Returns a udp endpoint provider.
	 */
	public static EndpointProvider<ConnectionAddress> udpProvider(NetworkExecutor executor, Address address, SocketAddress socketAddress) {
		return new UDPEndpointProvider(executor, address, socketAddress);
	}
	
	/**
	 * Returns a tcp endpoint provider.
	 *
	 * @param executor The executor the returned endpoint provider will use.
	 * @return Returns a tcp endpoint provider.
	 */
	public static EndpointProvider<SocketAddress> tcpProvider(NetworkExecutor executor) {
		return new TCPEndpointProvider(executor);
	}

	/**
	 * Returns a server endpoint.
	 *
	 * @param port The port the returned server endpoint will listen on.
	 * @return Returns a server endpoint.
	 * @throws IOException if there was a problem opening the server endpoint.
	 * @throws InterruptedException if the provider was interrupted while opening the server endpoint.
	 * @throws TimeoutException if the provider timed out while opening the server endpoint.
	 */
	public abstract ServerEndpoint open(int port) 
		throws IOException, InterruptedException, TimeoutException;

	/**
	 * Returns an endpoint.
	 *
	 * @param address The address associated with the returned endpoint.
	 * @return Returns an endpoint.
	 * @throws IOException if there was a problem opening the endpoint.
	 * @throws InterruptedException if the provider was interrupted while opening the endpoint.
	 */
	public abstract Endpoint open(A address) 
		throws IOException, InterruptedException;
		
	/**
	 * Shuts down this endpoint provider.
	 */
	public void shutdown() {}
	
	static abstract class SecureEndpoint implements Endpoint {
		// control flags
		static final byte INIT  = 1; 
		static final byte DATA  = 2; 
		static final byte RESET = 3; 
		static final byte CLOSE = 4; 
		byte flag = INIT;
		// handshake timeout in  seconds
		protected static final long HANDSHAKE_TIMEOUT = 5;
		protected KeyExchange exchanger;
		protected int protocolVersion;
		private CountDownLatch latch = new CountDownLatch(1);
		boolean isProxy;
		Address remote;
		
		protected synchronized Endpoint connect(boolean isProxy) throws IOException {
			this.isProxy = isProxy;
			
			// magic and version
			byte[] buf = new byte[10];
			NetVersion version = NetVersion.current();

			if(isProxy){
				buf[0] = INIT;
				buf[1] = (byte)version.protocolVersion;
				Magic.magic(buf, 2);
				writeRaw(buf);
				
				buf = readRaw();
				if(buf[0] != INIT){
					throw new IOException("Attempt to initialize connection with wrong control flag: "+buf[0]);
				}
				boolean b = Magic.verifyMagic(buf, 2);
				if(!b){
					System.out.println("Bad Voodoo");
					throw new IOException("Bad Voodoo");
				}
				protocolVersion = buf[1];
			}else{
				buf = readRaw();
				if(buf[0] != INIT){
					throw new IOException("Attempt to initialize connection with wrong control flag: "+buf[0]);
				}
				protocolVersion = Math.min(buf[1], version.protocolVersion);
				boolean b = Magic.verifyMagic(buf, 2);
				if(!b){
					// System.out.println("Bad Voodoo");
					throw new IOException("Bad Voodoo");
				}
				buf[1] = (byte)protocolVersion;
				writeRaw(buf);
			}

			// key exchange
			ByteStream stream = new ByteStream() {
				@Override
				public void write(byte[] b) throws IOException {
					byte[] d = new byte[b.length + 1];
					d[0] = flag;
					System.arraycopy(b, 0, d, 1, b.length);
					writeRaw(d);
				}
				@Override
				public byte[] read() throws IOException {
					byte[] b = readRaw();
					return extract(b, 1, b.length - 1);
				}
			};
			exchanger = new KeyExchange(isProxy, stream);
			try{
				exchanger.exchange();
			}catch(ArrayIndexOutOfBoundsException e){
				// ec will throw this if something is wonky
				throw new IOException(e);
			}
			
			flag = DATA;
			latch.countDown();
			
			return this;
		}
				
		protected abstract void writeRaw(byte[] b) throws IOException;
		protected abstract byte[] readRaw() throws IOException;
		
		@Override
		public void write(Encodable data) throws IOException {
			try{
				latch.await(HANDSHAKE_TIMEOUT, TimeUnit.SECONDS);
				exchanger.write(data);
			}catch(Exception e){
				throw new IOException(e);
			}
		}

		@Override
		public <T> T read(Decoder<T> decoder) throws IOException {
			try{
				latch.await(HANDSHAKE_TIMEOUT, TimeUnit.SECONDS);
				return exchanger.read(decoder);
			}catch(Exception e){
				throw new IOException(e);
			}
		}
	}
	
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
				//e.printStackTrace();
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
			}catch(Exception z){}
			
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
	
	// used by tcp endpoint provider
	static class SocketEndpoint extends SecureEndpoint {
		private final Socket socket;
		private VarInputStream in;
		private VarOutputStream out;

		SocketEndpoint(Socket socket) throws IOException {
			this.socket = socket;
			in = new VarInputStream(socket.getInputStream());
			out = new VarOutputStream(socket.getOutputStream());
		}

		@Override
		protected void writeRaw(byte[] b) throws IOException {
			out.writeBytes(b);
			out.flush();
		}
		
		@Override
		protected byte[] readRaw() throws IOException {
			return in.readBytes();
		}

		@Override
		public boolean isOpen() {return !socket.isClosed();}

		@Override
		public void close() throws IOException {
			if(!socket.isClosed()) socket.close();
		}
	}
}
