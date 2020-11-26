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
 
package rs.igram.kiribi.net.stack.natt;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import rs.igram.kiribi.net.stack.Processor;
import rs.igram.kiribi.net.stack.NetworkProtocol;
import rs.igram.kiribi.crypto.Address;
//import rs.igram.kiribi.crypto.Key;

import static rs.igram.kiribi.net.stack.NetworkProtocol.*;
import static rs.igram.kiribi.net.stack.natt.NATTProtocol.SessionState.*;
import static rs.igram.kiribi.net.stack.natt.NATTProtocol.SessionType.*;
import static rs.igram.kiribi.net.stack.natt.NATTProtocol.*;
import static rs.igram.kiribi.io.ByteUtils.*;

/**
 * 
 *
 * @author Michael Sargent
 */
public final class NATTProcessor extends Processor {
	private static final int SESSION_INIT_LIMIT 		= 30;
	private static final int SESSION_POST_INIT_LIMIT 	= 3;
	private static final int SESSION_INIT_DELAY 		= 100;
	private static final int SESSION_POST_INIT_DELAY 	= 50;
	static Random random = new Random();
	// session map
	final Map<Key,Session> sessions = Collections.synchronizedMap(new HashMap<>());
	// address map
	final Map<Key,Address> addresses = Collections.synchronizedMap(new HashMap<>());
	final Object obj = new Object(){};
	protected final SocketAddress server;
	CompletableFuture<SocketAddress> future;
	SocketAddress external;
	Consumer<DatagramPacket> consumer;
	Consumer<SessionEvent> listener;
	
	long start;
	int port;
	
	public NATTProcessor(SocketAddress server, int port, Consumer<SessionEvent> listener) {
		super(NetworkProtocol.NATT_PROTOCOL);
		
		this.server = server;
		this.listener = listener;
		this.port = port;
		System.out.println("NATTClient started on port: "+port+"...");
	}
	
	void notify(SessionEvent e) {
		submit(() -> listener.accept(e));
	}
/*	
	// natt server keep alive
	void keepalive() {
		while(!Thread.currentThread().isInterrupted()){
			try{
				socket.send(KA_PACKET);
				Thread.sleep(KA_INTERVAL);
			}catch(SocketException e){
				System.out.println("Socket closed");
				break;
			}catch(IOException e){
				System.out.println("Unexpected IOException");
				break;
			}catch(InterruptedException e){
				break;
			}
		}
	}
*/	
	@Override
	public void process(DatagramPacket p) {
		try{
		System.out.println("NATTClient.process: "+p.getSocketAddress());
		SocketAddress address = p.getSocketAddress();
		byte[] buf = p.getData();
		int l = p.getLength();
		if(address.equals(server)){
			processServerResponse(buf);
		// natt response - process	
		}else if(l == 9){
			processNATTResponse(p);
		}
		}catch(IOException e){
			e.printStackTrace();
		}
	}
	
	// --- server stuff ---
	private void processServerResponse(byte[] buf) throws IOException {
		// offset long session id + long natt id
		int c = buf[OFF_CMD]; 
		switch(c){
		// tunnel request from remote peer via server
		case TUN: 
			tun(buf);
			break;
		// external address - response from register request	
		case ADR: 
			adr(buf);
			break;
		// remote address - response from connect request
		case ADC: 
			adc(buf);
			break;
		// remote address not available - response from connect request	
		case ERR: 
			err(buf);
			break;
		default: System.out.println("Unknown: "+c);
		}
	}
	
	// tunnel request from remote peer via server
	private void tun(byte[] buf) throws IOException {
		try{
			final SocketAddress dst = inet(buf);
			submit(() -> {
				try{
					long start = System.currentTimeMillis();
					boolean natted = natt(buf).get(2000, TimeUnit.MILLISECONDS);
					System.out.println("TUNNEL: "+natted+" "+(System.currentTimeMillis() - start) +" "+dst );
				}catch(Throwable e){
					e.printStackTrace();
				}
			});
		}catch(UnknownHostException e){
			throw new IOException(e);
		}
	}
	
	// external address returned from server after registration
	private void adr(byte[] buf) throws IOException {
		try{
			external = inet(buf);
			System.out.println("External IP Address: "+external);
		}catch(UnknownHostException e){
			throw new IOException(e);
		}
	}
	
	// remote address returned from server after connect if the dst is registered
	private void adc(byte[] buf) throws IOException {
		try{
			SocketAddress dst = inet(buf);
			future.complete(dst);
		}catch(UnknownHostException e){
			throw new IOException(e);
		}
	}

	//  returned from server after connect if the dst is not registered
	private void err(byte[] buf) {
		if(future != null) future.completeExceptionally(new IOException("Address not registered"));
	}
	
	public void register(Address address) throws IOException {
		byte[] buf = new byte[512];
		protocol(buf, NetworkProtocol.NATT_PROTOCOL);
		cmd(buf, REG);
		address(buf, address);
		mux.write(buf, server);
	}
	
	public Key connect(Address address) throws IOException {
		synchronized(obj){
			future = new CompletableFuture<SocketAddress>();
			try{
				long id = random.nextLong();
				start = System.currentTimeMillis();
				byte[] buf = new byte[512];
				protocol(buf, NetworkProtocol.NATT_PROTOCOL);
				id(buf, id);
				cmd(buf, CON);
				address(buf, address);
				submit(() -> {
					// todo
					try{
						mux.write(buf, server);
					}catch(IOException e){
						e.printStackTrace();
					}
				});
				
				SocketAddress dst = future.get(500, TimeUnit.MILLISECONDS);
				boolean natted = natt(dst, id).get(5000, TimeUnit.MILLISECONDS);
				System.out.println("NATT connect: "+natted + " "+(System.currentTimeMillis() - start));
				return natted ? new Key(dst, id) : null;
			}catch(Throwable e){
				throw new IOException(e);
			}finally{
				future = null;
			}
		}
	}
	
	// --- data stuff ---
	private void processData(DatagramPacket p){
		System.out.println("NATTClient.processData: "+p.getSocketAddress());
		if(consumer != null) consumer.accept(p);
		notify(new SessionEvent(new Key(p), SOCKET, AVAILABLE, p));
	}
	
	// --- natt stuff ---
	// server side
	private Future<Boolean> natt(byte[] buf) {
		try{
			SocketAddress dst = inet(buf);
			long id = id(buf);
			Key key = new Key(dst, id);
			System.out.println("natt server: "+id+" "+dst);
			Session session = sessions.get(key);
			if(session == null){
				session = new Session(dst, id);
				sessions.put(key, session);
				notify(new SessionEvent(key, NATT, INIT, null));
			}
			return session.result();
		}catch(Exception e){
			CompletableFuture f = new CompletableFuture();
			f.completeExceptionally(e);
			return f;
		}
	}
	
	// client side
	private Future<Boolean> natt(SocketAddress dst, long id) {
		System.out.println("natt client: "+id+" "+dst);
		Key key = new Key(dst, id);
		Session session = sessions.get(key);
		if(session == null){
			session = new Session(dst, id);
			sessions.put(key, session);
			notify(new SessionEvent(key, NATT, INIT, null));
		}
		return session.result();
	}
	
	private void processNATTResponse(DatagramPacket p){
		Session session = sessions.get(new Key(p));
		if(session != null && !session.future.isDone()) session.process();
	}

	private static void id(byte[] b, long id) {
		put(b, OFF_ID, id);
	}
	
	private static void cmd(byte[] b, byte cmd) {
		b[OFF_CMD] = cmd;
	}
	
	private static void protocol(byte[] b, byte protocol) {
		b[0] = protocol;
	}
	
	private static void address(byte[] b, Address address) {
		System.arraycopy(address.encodeUnchecked(), 0, b, OFF_DATA, 20);
	}
	
	private static void inet(byte[] b, SocketAddress address) {
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

	private static long id(byte[] b) {
		return getLong(b, OFF_ID);
	}
	
	private static byte cmd(byte[] b) {
		return b[OFF_CMD];
	}
	
	private static byte protocol(byte[] b) {
		return b[0];
	}
	
	private static Address address(byte[] b) {
		return new Address(extract(b, OFF_DATA, 20));
	}
	
	private static SocketAddress inet(byte[] src) throws UnknownHostException {
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
	
	private class Session {
		long id;
		final SocketAddress address;
		final DatagramPacket syn;
		final Object lock = new Object(){};
		final Key key;
		int cnt = 0;
		int limit = SESSION_INIT_LIMIT;//15;
		boolean marked = false;
		
		 // millis
		long delay = SESSION_INIT_DELAY;//75;
		Future<Boolean> future;
		
		Session(SocketAddress address, long id) {
			this.address = address;
			this.id = id;

			key = new Key(address, id);
			byte[] buf = new byte[9];
			protocol(buf, NetworkProtocol.NATT_PROTOCOL);
			id(buf, id);
			syn = new DatagramPacket(buf, 9, address);			
			future = submit(this::natt);
		}
		
		boolean natt() throws IOException {
			long localDelay = SESSION_INIT_DELAY;//75;
			for(;;){
				synchronized(lock){
					if(cnt == limit){
						NATTProcessor.this.notify(new SessionEvent(key, NATT, marked ? AVAILABLE : FAILED, null));
						return marked ? true : false;
					}
					localDelay = delay;
					cnt++;
				}
				try{
					mux.write(syn);
					Thread.sleep(localDelay);
				}catch(InterruptedException e){
					return false;
				}
			}
		}
		
		void process(){
			synchronized(lock) {
				if(!marked){
					// want to send two syns after we recieve a syn
					limit = cnt + SESSION_POST_INIT_LIMIT;// 2;
					// shorten delay
					delay = SESSION_POST_INIT_DELAY;//30;
					marked = true;
				}
			}
		}
		
		Future<Boolean> result() {return future;}
	}	
	
	// key for session and address maps
	public static class Key {
		public final SocketAddress address;
		long id;
		
		Key(SocketAddress address, long l){
			this.address = address;
			id = l;
		}
		
		Key(DatagramPacket p){
			address = p.getSocketAddress();
			id = id(p.getData());
		}
		
		public long longId() {
			return id;
		}
		
		@Override
		public String toString() {
			return "Key: "+address+"["+longId()+"]";
		} 
		
		@Override
		public boolean equals(Object o){
			if(o instanceof Key){
				Key k = (Key)o;
				return address.equals(k.address) && id == k.id;
			}else{
				return false;
			}
		}
		
		@Override
		public int hashCode(){
			return (31 * ((31 * 17) + (int)id)) + address.hashCode(); 
		}
	}
	
	public static class SessionEvent {
		final Key key;
		public final SessionType type;
		final SessionState state;
		public final DatagramPacket data;
		
		SessionEvent(Key key, SessionType type, SessionState state, DatagramPacket data) {
			this.key = key;
			this.type = type;
			this.state = state;
			this.data = data;
		}
		
		@Override
		public String toString() {
			return "SessionEvent: "+key+" "+type+" "+state+" "+(data == null);
		} 
	}
}
