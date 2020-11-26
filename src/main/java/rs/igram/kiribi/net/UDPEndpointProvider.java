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
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import rs.igram.kiribi.crypto.Address;
import rs.igram.kiribi.crypto.Challenge;
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

import static rs.igram.kiribi.net.stack.natt.NATTProcessor.SessionEvent;
import static rs.igram.kiribi.net.stack.natt.NATTProtocol.SessionType.SOCKET;
import static rs.igram.kiribi.net.stack.NetworkProtocol.*;

/**
 * UDP Endpoint Provider
 *
 * @author Michael Sargent
 */
final class UDPEndpointProvider extends EndpointProvider<ConnectionAddress> {
	// todo: adjust timeout based on message size?
	// send timeout ms (30 seconds)
	static final long DEFAULT_SEND_TIMEOUT = 30_000;
	// activity timeout ms (30 minutes)
	static final long DEFAULT_ACTIVITY_TIMEOUT = 30 * 60 * 1_000;
	// activity sweep interval ms (3 seconds)
	static final long DEFAULT_SWEEP_INTERVAL = 3_000;
	
	static final boolean isIPV6Supported = false;

	final Object lock = new Object(){};
	final SocketAddress serverAddress;
	final Address address;
	final Map<SocketAddress,Address> addresses = new HashMap<>();
	final Map<SocketAddress,Mux> muxes = new HashMap<>();
	final Map<Address,Mux> map = new HashMap<>();
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
	private int port = -1;
	
	public UDPEndpointProvider(NetworkExecutor executor, Address address, SocketAddress serverAddress) {
		super(executor);
		
		this.address = address;
		this.serverAddress = serverAddress;
		
		me = address;
	}

	private void start(int port) {
		startIPV4(port);
	}

	private void startIPV4(int port) {
		stack = new DatagramIPV4Stack(executor, address, serverAddress, port, (s,b) -> accept(s, b), this::onIncoming, this::onExpired);
		stack.configure();
		stack.start();
			
		activityMonitor = executor.submit(this::monitorActivity);
			
		try{
			stack.register();
		}catch(Throwable t){
			// todo
			t.printStackTrace();
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
		Mux mux = null;
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
	public synchronized ServerEndpoint open(int p)
		throws IOException, InterruptedException, TimeoutException{
		
		if(port != p){
			if(port != -1){
				shutdown();
			}
			start(p);
			port = p;
		}
		return server;
	}
	
	@Override
	public synchronized void shutdown() {
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
		
		port = -1;
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
			Mux mux = muxes.get(address);
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

	private Mux openMux(SocketAddress sa, boolean isProxy) {
		MUXEndpoint ep = new MUXEndpoint(sa);
		Mux mux = new Mux(ep);
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
	
	private void resetMux(Mux mux, SocketAddress sa, boolean isProxy) {
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
	
	private final class Mux extends MUX<MUXEndpoint> {
		Address address;
		
		Mux(SocketAddress sa) {
			this(new MUXEndpoint(sa));
		}
		
		Mux(MUXEndpoint root) {
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
}
