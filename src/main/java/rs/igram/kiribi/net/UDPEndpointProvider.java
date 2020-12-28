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
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import rs.igram.kiribi.crypto.KeyExchange;
import rs.igram.kiribi.crypto.SignedData;
import rs.igram.kiribi.io.ByteStream;
import rs.igram.kiribi.io.Decoder;
import rs.igram.kiribi.io.Encodable;
import rs.igram.kiribi.io.VarInput;
import rs.igram.kiribi.io.VarInputStream;
import rs.igram.kiribi.io.VarOutput;
import rs.igram.kiribi.io.VarOutputStream;
import rs.igram.kiribi.net.stack.*;
import rs.igram.kiribi.net.stack.natt.AddressNotRegisteredException;

import static rs.igram.kiribi.net.stack.natt.NATTProcessor.SessionEvent;
import static rs.igram.kiribi.net.stack.natt.NATTProtocol.SessionType.SOCKET;
import static rs.igram.kiribi.net.stack.NetworkProtocol.*;

import static java.util.logging.Level.*;

/**
 * UDP Endpoint Provider
 *
 * @author Michael Sargent
 */
final class UDPEndpointProvider extends EndpointProvider {
	private static final Logger LOGGER = Logger.getLogger(UDPEndpointProvider.class.getName());
	
	// todo: adjust timeout based on message size?
	// send timeout ms (30 seconds)
	static final long DEFAULT_SEND_TIMEOUT = 30_000;
	// activity timeout ms (30 minutes)
	static final long DEFAULT_ACTIVITY_TIMEOUT = 30 * 60 * 1_000;
	// activity sweep interval ms (3 seconds)
	static final long DEFAULT_SWEEP_INTERVAL = 3_000;
	
	static final boolean isIPV6Supported = false;

	/** The executor associated with this endpoint provider. */
	final NetworkExecutor executor;
	
	final Object lock = new Object(){};
	final SocketAddress serverAddress;
	final Map<SocketAddress,Address> addresses = new HashMap<>();
	final Map<SocketAddress,Muxx> muxes = new HashMap<>();
	final Map<Address,Muxx> map = new HashMap<>();
	// local connections (from/to ourself)
	final Map<Long,LocalConnection> localConnections = new HashMap<>();
	// our address
	private Address me;
	
	private long activityTimeout = DEFAULT_ACTIVITY_TIMEOUT;
	private long sendTimeout = DEFAULT_SEND_TIMEOUT;
	private long sweepInterval = DEFAULT_SWEEP_INTERVAL;
	private ServerUDPEndpoint server = new ServerUDPEndpoint();
	private Consumer<Endpoint> consumer;
	private DatagramSocket nattSocket;
	private DatagramStack stack;
	private Future<?> activityMonitor;
	private boolean initialized = false;
	private InetSocketAddress socketAddress;
	
	public UDPEndpointProvider(NetworkExecutor executor, InetSocketAddress socketAddress, Address address, SocketAddress serverAddress) {
		super(socketAddress, address);
		
		this.executor = executor;
		this.serverAddress = serverAddress;
		
		me = address;
	}

	private void start() {
		synchronized(this) {
			if (initialized) return;
			startIPV4(socketAddress);
			initialized = true;
		}
	}

	private void startIPV4(InetSocketAddress socketAddress) {
		stack = new DatagramIPV4Stack(executor, address, serverAddress, socketAddress, (s,b) -> accept(s, b), this::onIncoming, this::onExpired);
		stack.configure();
		stack.start();
			
		activityMonitor = executor.submit(this::monitorActivity);
			
		try{
			stack.register();
		}catch(Throwable t){
			// todo
			LOGGER.log(SEVERE, t.toString(), t);
		}
	}

	private void monitorActivity() {
		while(!Thread.currentThread().isInterrupted()){
			try{
				TimeUnit.SECONDS.sleep(sweepInterval);
			}catch(InterruptedException e){
				return;
			}
			synchronized(lock){
				long now = System.currentTimeMillis();
				muxes.values()
					 .stream()
					 .filter(m -> m.root.inactive(now))
					 .collect(Collectors.toSet())
					 .forEach(m -> m.dispose(true));
			}
		}
	}
/*	
	private void kap(DatagramPacket p) {
		if(kap != null) kap.process(p);
	}
*/	
	private void onIncoming(SocketAddress a) {}
	
	private void onExpired(Set<SocketAddress> s) {
		// lost keep alive signal from remote peer - dispose connection
		synchronized(lock){
			s.stream()
			 .map(muxes::get)
			 .filter(m -> m != null)
			 .collect(Collectors.toSet())
			 .forEach(m -> m.dispose(false));
		 }
	}

	@Override
	public Endpoint open(ConnectionAddress address)
		throws IOException, InterruptedException {

		start();
		
		long id = address.id;
		Address host = address.address;
		// result
		Endpoint endpoint = null;
		
		// check here to avoid deadlock - see ServiceAdmin.initialize()
		
		// check if we are connecting to ourself
		if(me.equals(host)){
			return localConnections.computeIfAbsent(id, k -> {
				try{
					LocalConnection local = new LocalConnection();
					// connect service
					executor.submit(() -> consumer.accept(local.service));
					return local;
				}catch(IOException e){
					// shouldn't happen
					throw new UncheckedIOException(e);
				}
			}).proxy;
		}
		
		// check if mux already exists for the address
		Muxx mux = null;
		synchronized(lock){
			mux = map.get(host);
		}
		if(mux != null){
			// ugh!
			if(mux.isClosed() || (mux.root != null && !mux.root.isOpen())){
				synchronized(lock){
					map.remove(host);
				}
			}else{
				return mux.open(id);
			}
		}
		
		SocketAddress inet = null;
		try{ 

// TODO ADD CACHE BEGIN LOOP
			inet = stack.connect(host);
			// natt failed
			if(inet == null) throw new IOException("CONNECT failed");
			
			// mux
			synchronized(lock){
				mux = muxes.get(inet);
				if(mux == null){
					mux = openMux(inet, true);
				}else if(mux.isClosed()){
					muxes.remove(inet);
					mux = openMux(inet, true);
				}
			}
			// mux failed - shouldn't happen?
			if(mux == null) throw new IOException("MUX failed");
			
			if(mux.address == null) mux.address = host;
			
			endpoint = mux.open(id);
// TODO ADD CACHE LOOP END			
		//}
		
		}catch(AddressNotRegisteredException e){
			synchronized(lock){
				map.remove(host);
			}
			throw new NoRouteToHostException("Address not registered");
		}catch(Exception e){
			synchronized(lock){
				map.remove(host);
			}
			throw new IOException(e);
		}		
		
		synchronized(lock){
			map.put(host, mux);
			addresses.put(inet, host);
		}
		// kap
//TODO		if(!isIPV6Supported) kap.add(inet);
			
		return endpoint;
	}

	@Override
	public synchronized ServerEndpoint server()
		throws IOException, InterruptedException, TimeoutException{
		
		start();
		return server;
	}
	
	@Override
	public synchronized void shutdown() {
		synchronized(this) {
			muxes.values().forEach(m -> {
				executor.submit(() -> m.dispose(false));
			});
			muxes.clear();
			// give some time to notify remote peers
			try{
				TimeUnit.MILLISECONDS.sleep(300);
			}catch(Exception z){}
			
			if(activityMonitor != null) activityMonitor.cancel(true);
			if(stack != null) stack.shutdown();
		
			activityMonitor = null;
			stack = null;
			map.clear();
		}
	}

	void process(SessionEvent e) {		
		if(nattSocket != null && e.type == SOCKET){ 
			try{
				stack.process(e.data);
			}catch(Exception x){
				// ignore ?
			}
		}
	}

	// rmp consumer
	private void accept(SocketAddress address, byte[] data) {
		byte flag = data[0];
		// if already opened as proxy, 2nd arg to mux ignored so if 
		// it doesn't already exit assume its a server
		
		synchronized(lock){
			Muxx mux = muxes.get(address);
			switch(flag){
			case SecureEndpoint.INIT:
				if(mux == null){
					// open
					mux = openMux(address, false);
				}else{
					// reset local peer
					if(mux.root.flag != SecureEndpoint.INIT) resetMux(mux, address, false);
				}
				mux.root().receive(data);
				break;
			case SecureEndpoint.DATA:
				if(mux == null){
					// reset remote peer
					byte[] b = new byte[]{SecureEndpoint.RESET};
					try{
						stack.send(address, b);
					}catch(InterruptedException x){}
				}else{
					// process
					mux.root().receive(data);
				}
				break;
			case SecureEndpoint.RESET:
				if(mux != null) resetMux(mux, address, true);
				break;
			case SecureEndpoint.CLOSE:
				if(mux != null) mux.dispose(false);
				break;
			}
		}
	}

	private Muxx openMux(SocketAddress sa, boolean isProxy) {
		MUXEndpoint ep = new MUXEndpoint(sa);
		Muxx mux = new Muxx(ep);
		muxes.put(sa, mux);
		executor.submit(() -> {
			try{
				ep.connect(isProxy);
			}catch(Throwable e){
				muxes.remove(sa);
				// todo ?
			}
		});
		
		return mux;
	}
	
	private void resetMux(Muxx mux, SocketAddress sa, boolean isProxy) {
		MUXEndpoint ep = new MUXEndpoint(sa);
		executor.submit(() -> {
			try{
				ep.connect(isProxy);
			}catch(Exception e){
				muxes.remove(sa);
				// todo ?
			}
		});
		mux.reset(ep);
	}

	private class ServerUDPEndpoint implements ServerEndpoint {
		@Override
		public void accept(Consumer<Endpoint> consumer) throws IOException {
			UDPEndpointProvider.this.consumer = consumer;
		}

		@Override
		public boolean isOpen() {return true;}

		@Override
		public void close() throws IOException {}
	}
	
	private final class Muxx extends MUX<MUXEndpoint> {
		Address address;
		
		Muxx(SocketAddress sa) {
			this(new MUXEndpoint(sa));
		}
		
		Muxx(MUXEndpoint root) {
			super(root, consumer);			
		}
		
		@Override
		protected void onClosed(long id) {
		}
		
		@Override
		protected void onDisposed(MUX mux) {
			synchronized(lock){
				map.remove(address);
				muxes.remove(root.address);
			}
		}
	}
	
	private final class MUXEndpoint extends SecureEndpoint {
		final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
		final SocketAddress address;
		boolean isClosed;
		long mark;

		MUXEndpoint(SocketAddress address) {
			this.address = address;
		}
		
		@Override
		protected Endpoint connect(boolean isProxy) throws IOException {
			CompletableFuture<Endpoint> f = new CompletableFuture<>();
			executor.submit(() -> {
				try{
					Endpoint ep = super.connect(isProxy);
					f.complete(ep);
				}catch(IOException e){
					f.completeExceptionally(e);
				}
			});
			
			try{
				return f.get(HANDSHAKE_TIMEOUT, TimeUnit.SECONDS);
			}catch(InterruptedException e){
				// ignore
				return null;
			}catch(Exception e){
				throw new IOException(e);
			}
		}
			
		@Override
		protected void writeRaw(byte[] b) throws IOException {
			if(isClosed) throw new IOException("SecureEndpoint is closed");
			try{
				boolean success = stack.send(address, b).get(sendTimeout, TimeUnit.MILLISECONDS);
				if(!success){
					throw new IOException("Send failed...");
				}else{
					// mark activity
					if(b[0] == SecureEndpoint.DATA) mark = System.currentTimeMillis();
				}
			}catch(InterruptedException e){
				// ignore
			}catch(TimeoutException e){
				throw new IOException(e);
			}catch(ExecutionException e){
				throw new IOException(e.getCause());
			}
		}
		
		@Override
		protected byte[] readRaw() throws IOException {
			if(isClosed) throw new IOException("SecureEndpoint is closed");
			try{
				return queue.take();
			}catch(InterruptedException e){
				throw new IOException(e);
			}
		}
		
		int receive(byte[] data) {
			if(isClosed) return -1;
			// mark activity
			if(data[0] == SecureEndpoint.DATA) mark = System.currentTimeMillis();
			queue.add(data);
			return queue.size();
		}
		
		boolean inactive(long now) {
			return now - mark > activityTimeout;
		}
		
		@Override
		public void close() {
			isClosed = true;
			queue.clear();
			synchronized(lock){
				muxes.remove(address);
				Address addr = addresses.remove(address);
				map.remove(addr);
			}
		}
		
		@Override
		public boolean isOpen() {
			// todo
			return !isClosed;
		}
	}
	
	// connection to ourself (same host address)
	private class LocalConnection {
		LocalEndpoint proxy;
		LocalEndpoint service;
		
		LocalConnection() throws IOException {
			PipedInputStream proxyIn = new PipedInputStream();
			PipedOutputStream serviceOut = new PipedOutputStream(proxyIn);
			
			PipedInputStream serviceIn = new PipedInputStream();
			PipedOutputStream proxyOut = new PipedOutputStream(serviceIn);
			
			proxy = new LocalEndpoint(proxyIn, proxyOut);
			service = new LocalEndpoint(serviceIn, serviceOut);
		}
		
		private class LocalEndpoint implements Endpoint {
			private VarInputStream in;
			private VarOutputStream out;

			LocalEndpoint(InputStream i, OutputStream o){
				in = new VarInputStream(i);
				out = new VarOutputStream(o);
			}
			
			@Override
			public void write(Encodable data) throws IOException {
				out.write(data);
			}

			@Override
			public <T> T read(Decoder<T> decoder) throws IOException {
				return in.read(decoder);
			}

			@Override
			public boolean isOpen() {return true;}

			@Override
			public void close() throws IOException {
				// ignore
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
