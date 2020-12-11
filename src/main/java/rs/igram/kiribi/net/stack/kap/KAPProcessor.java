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
 
package rs.igram.kiribi.net.stack.kap;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

import rs.igram.kiribi.net.stack.Processor;
import rs.igram.kiribi.net.stack.NetworkProtocol;

import static rs.igram.kiribi.net.stack.kap.KAPProtocol.*;

import static java.util.logging.Level.*;

/**
 * 
 *
 * @author Michael Sargent
 */
/*
 TODO - 
 enhance logging
 unit tests
 */
public class KAPProcessor extends Processor {	
	private static final Logger LOGGER = Logger.getLogger(KAPProcessor.class.getName());
	
	final byte[] DATA = {NetworkProtocol.KAP_PROTOCOL};
	final Map<SocketAddress,Entry> map = new HashMap<>();
	final Consumer<SocketAddress> onIncoming;
	final Consumer<Set<SocketAddress>> onExpired;
	// milliseconds
	long delay = 5;
	int maxMisses = 4;
	DatagramSocket socket;
	Future<?> timer;
	
	public KAPProcessor(Consumer<SocketAddress> onIncoming, Consumer<Set<SocketAddress>> onExpired) {
		super(NetworkProtocol.KAP_PROTOCOL);
		
		this.onIncoming = onIncoming;
		this.onExpired = onExpired;
	}

	@Override
	public void start() {	
		timer = submit(() -> {
			while(!Thread.currentThread().isInterrupted()){
				try{	
					TimeUnit.SECONDS.sleep(delay);
				}catch(InterruptedException e){
					return;
				}
				sweep();
			}
		});
		
		submit(this::keepalive);
	}	
	
	@Override
	public void process(DatagramPacket p) {
		SocketAddress a = p.getSocketAddress();
		if(a.equals(SERVER_ADDRESS)) return;
		Entry e = null;
		synchronized(map){
			e = map.computeIfAbsent(a, k -> {
				if(onIncoming != null) submit(() -> onIncoming.accept(a));
				return new Entry(true);
			});
		}
		e.hit();
		if(e.incoming){
			try{
				mux.write(p);
			}catch(Throwable x) {
				LOGGER.log(SEVERE, x.toString(), x);
			}	
		}
	}
	
	@Override
	public void shutdown() {
		try{
			if(timer != null) timer.cancel(true);
		}catch(Throwable e) {}	
	}
	
	// natt server keep alive
	private void keepalive() {
		while(!Thread.currentThread().isInterrupted()){
			try{
				socket.send(KA_PACKET);
				Thread.sleep(KA_INTERVAL);
			}catch(SocketException e){
				LOGGER.log(FINER, "Socket closed");
				break;
			}catch(IOException e){
				LOGGER.log(FINER, "Unexpected IOException");
				break;
			}catch(InterruptedException e){
				break;
			}
		}
	}
	
	private void sweep() {
		Set<SocketAddress> expired = new HashSet<>();
		Set<SocketAddress> notify = new HashSet<>();
		synchronized(map){
			map.entrySet().forEach(e -> {
				SocketAddress a = e.getKey();
				Entry v = e.getValue();
				if(v.expired()){
					expired.add(a);
				}else if(!v.incoming){
					notify.add(a);
				}
			});
			map.keySet().removeAll(expired);
		}
		
		submit(() -> notify.forEach(this::send));
		if(onExpired != null && !expired.isEmpty()) submit(() -> onExpired.accept(expired));
	}
	
	private void add(SocketAddress a) {
		synchronized(map){
			map.put(a, new Entry(false));
		}
	}
	
	private void remove(SocketAddress a) {
		synchronized(map){
			map.remove(a);
		}
	}
	
	private void send(SocketAddress a) {
		try{
			mux.write(new DatagramPacket(DATA, 1, a));
		}catch(IOException e){
			LOGGER.log(SEVERE, e.toString(), e);
		}
	}

	private class Entry {
		final boolean incoming;
		boolean hit;
		int missCnt;
		
		Entry(boolean incoming) {
			this.incoming = incoming;
		}
		
		void hit() {
			hit = true;
			missCnt = 0;
		}
		
		boolean expired() {
			if(hit){
				hit = false;
				return false;
			}else{
				if(++missCnt < maxMisses){
					hit = false;
					return false;
				}else{
					return true;
				}
			}
		}
	}
}