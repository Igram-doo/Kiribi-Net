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
 
package rs.igram.kiribi.net.stack.rmp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

import rs.igram.kiribi.net.stack.NetworkMux;
import rs.igram.kiribi.net.stack.Processor;
import rs.igram.kiribi.net.stack.NetworkProtocol;

import static rs.igram.kiribi.net.stack.rmp.RMPProcessor.State.*;
import static rs.igram.kiribi.io.ByteUtils.*;

import static java.util.logging.Level.*;

/**
 * Reliable Message Protocol Processor
 *
 * @author Michael Sargent
 */
/*
 -- reliable message protocol --
 
 break data into chunks not exceeding MTU - headers
 send 8 chunks (or possibly less if last the last part of data)
 wait for response
 if returned message indicates missing chunks, resend them
 continue until all 8 chunks received
 move to next 8 chunks
 continue until all data received
 */
/*
 TODO - 
 enhance logging
 unit tests
 */
public final class RMPProcessor extends Processor {
	private static final Logger LOGGER = Logger.getLogger(RMPProcessor.class.getName());
	
	// received by receiver sessions
	static final byte SYN = 1;
	static final byte DAT = 2;
	static final byte RTM = 3;
	// received by transmitter sessions
	static final byte ACK = 11;
	static final byte FIN = 12;	
	static final byte NAK = 13;
	
	// ack/fin/nak response timeout
	static final long DEFAULT_SESSION_TIMEOUT = 200;
	static final int MTU = 1500;
	// avoid fragmentation: mtu - ip/udp headers
	static final int MAX_PAYLOAD_SIZE = 1472; 
	// protocol [1 byte] + session_id [4 bytes] + cmd [1 byte] + seqno [4 bytes]
	static final int HEADER_SIZE = 10; 
	static final int MAX_CHUNK_SIZE = MAX_PAYLOAD_SIZE - HEADER_SIZE;
	static final int MAX_MESSAGE_SIZE = Integer.MAX_VALUE * MAX_PAYLOAD_SIZE;
	// number of chunks in all segments except possibly the last
	static final int DEFAULT_SEGMENT_SIZE = 8;
	
	// timer interval (ms)
	static final long TIMER_INTERVAL = 200;
	
	final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
	final Map<Key, TransmittingSession> transmitters = Collections.synchronizedMap(new HashMap<>());
	final Map<Key, ReceivingSession> receivers = Collections.synchronizedMap(new HashMap<>());
	// avoid concurrent modifications to session maps during timeout/dispose
	final Semaphore semaphore = new Semaphore(1);
	final BiConsumer<SocketAddress,byte[]> consumer;
	
	long sessionCounter = 0l;
	// timer mark
	long timerMark;
	Future<?> processor;
	
	public RMPProcessor(BiConsumer<SocketAddress,byte[]> consumer) {
		super(NetworkProtocol.RMP_PROTOCOL);
		
		this.consumer = consumer;
	}
	
	@Override
	public void start() {
		processor = submit(() -> {
			int cnt = 0;
			while(!Thread.currentThread().isInterrupted()){			
				try{
					Object o = queue.poll(25, TimeUnit.MILLISECONDS);
					if(o == null){
						checkTimeouts();
						cnt = 0;
						continue;
					}else if(o instanceof DatagramPacket){
						doProcess((DatagramPacket)o);
						// check timeouts every third process 
						if((cnt++) % 200 == 0){
							cnt = 0;
							checkTimeouts();
						}
					}else if(o instanceof SendRequest){
						doSend((SendRequest)o);
						// check timeouts every third process 
						if((cnt++) % 200 == 0){
							cnt = 0;
							checkTimeouts();
						}
					}
				}catch(InterruptedException e){
					return;
				}catch(Exception e){
					// ignore
				}
			}
		});
		timerMark = System.currentTimeMillis();
		LOGGER.log(FINE, "RMP started...");
	}
	
	public void process(DatagramPacket p){		
		queue.add(p);
	}
	
	@Override
	public void shutdown() {
		processor.cancel(true);
	}
	
	public Future<Boolean> send(SocketAddress address, byte[] data) throws InterruptedException {
		SendRequest request = new SendRequest(address, data);
		queue.add(request);
		
		return request.future;
	}
	
	private void doSend(SendRequest request) throws InterruptedException {
		long id = sessionCounter++;
		TransmittingSession session = new TransmittingSession(request.address, id, request.future);
		transmitters.put(new Key(request.address, id), session);
		session.transmit(request.data);
	}
	
	private void doProcess(DatagramPacket p){		
		SocketAddress address = p.getSocketAddress();
		byte[] buf = p.getData();
		long id = sessionId(buf);
		Key key = new Key(address, id);
		byte cmd = cmd(buf);
		if(cmd < 10){
			ReceivingSession session = cmd == SYN ?
				receivers.computeIfAbsent(key, k -> {
					return new ReceivingSession(address, id);
				}) : 
				receivers.get(key);
			// ignore if already disposed
			if(session != null) session.process(cmd, p);
		}else{
			TransmittingSession session = transmitters.get(key);
			// ignore if no corresponding transmitter
			if(session != null) session.process(cmd, p);
		}
	}
	
	private void checkTimeouts() {
		long now = System.currentTimeMillis();
		boolean required = transmitters.size() > 0 || receivers.size() > 0;
		if(required && now - timerMark > TIMER_INTERVAL){
			timerMark = now;
			try{
				semaphore.acquire();
				Stream.concat(
					transmitters.values().stream(), 
					receivers.values().stream()
				).forEach(s -> s.check());
			}catch(InterruptedException e){
				// ignore
			}finally{
				semaphore.release();
			}
		}
	}
	
	static long sessionId(byte[] src) {
		return bytesToUnsignedInt(src, 1);
	}
	
	static byte cmd(byte[] src) {
		return src[5];
	}
	
	static long seqno(byte[] src) {
		return bytesToUnsignedInt(src, 6);
	}
	
	// compute the sequence number from the segment number and chunk index in the segment
	static long seqno(long segno, int index) {
		// 8 chunks in a segment
		return segno * DEFAULT_SEGMENT_SIZE + index;
	}
	
	// compute the segment number from the sequence number
	static long segno(long seqno) {
		// 8 chunks in a segment
		return seqno / DEFAULT_SEGMENT_SIZE;
	}
	
	// compute the index within the current segment from sequence number
	static int index(long seqno, long segno) {
		return (int)(seqno - (segno * DEFAULT_SEGMENT_SIZE));
	}
	
	abstract class Session {
		SocketAddress address;
		State state = INIT;
		
		// -- timeout stuff --
		long mark;
		long timeout;
		int retry;
		int maxRetries = 25;
		
		// -- data stuff --
		byte[] data;
		// total length of message
		long length;
		// session id
		long id;
		// current segment number
		long segNo;
		// max segments
		long maxSegs;
		// number of chunks in the final segement
		int finalSegSize;
		// max sequence number
		long maxSeqs;
		// size of final chunk
		int finalChunkSize;
		
		Session(SocketAddress address, long id) {
			this(address, id, DEFAULT_SESSION_TIMEOUT);
		}
		
		Session(SocketAddress address, long id, long timeout) {
			this.timeout = timeout;
			this.address = address;
			this.id = id;
		}
		
		abstract void process(byte cmd, DatagramPacket p);
		abstract void dispose();
		abstract void fail();
		abstract void completed();
		
		// -- packet stuff --
		// syn/fin - note: len is segno for fin
		int pack(byte cmd, long len, byte[] dst) {
			dst[0] = protocol;
			unsignedIntToBytes(id, dst, 1);
			dst[5] = cmd;
			unsignedIntToBytes(len, dst, 6);
		
			return 10;
		}
		
		// ack
		int pack(byte cmd, byte[] dst) {
			dst[0] = protocol;
			unsignedIntToBytes(id, dst, 1);
			dst[5] = cmd;
		
			return 6;
		}
		
		// data/rtm
		int pack(byte cmd, long seqno, byte[] data, int offset, int len, byte[] dst) {
			dst[0] = protocol;
			unsignedIntToBytes(id, dst, 1);
			dst[5] = cmd;
			unsignedIntToBytes(seqno, dst, 6);
			System.arraycopy(data, offset, dst, 10, len);
		
			return 10 + len;
		}
	
		// nak
		int pack(byte cmd, long segno, byte b, byte[] dst) {
			dst[0] = protocol;
			unsignedIntToBytes(id, dst, 1);
			dst[5] = cmd;
			unsignedIntToBytes(segno, dst, 6);
			dst[10] = b;
		
			return 11;
		}
		
		// -- timeout stuff --
		void mark(boolean timeout) {
			mark = System.currentTimeMillis();
			// increment retry on timeout otherwise reset retry to 0
			retry = timeout ? retry + 1 : 0;
		}
		
		long elapsed() {
			return System.currentTimeMillis() - mark;
		}
		
		void check() {
			if(elapsed() > timeout) timeout();
		}
		
		void timeout() {}
		
		DatagramPacket packet() {
			byte[] buf = new byte[MAX_PAYLOAD_SIZE];
			return new DatagramPacket(buf, MAX_PAYLOAD_SIZE, address);
		}
		
		void deliver(DatagramPacket p) {
			try{
				mux.write(p);
			}catch(Exception e){
				// ignore
			}
		}
		
		void length(long length) {
			this.length = length;
			
			// sequence
			int remainder = (int)length % MAX_CHUNK_SIZE;
			maxSeqs = length / MAX_CHUNK_SIZE + (remainder > 0 ? 1 : 0);
			finalChunkSize = remainder > 0 ? remainder : MAX_CHUNK_SIZE;
			// segment
			remainder = (int)maxSeqs % DEFAULT_SEGMENT_SIZE;
			maxSegs = maxSeqs / DEFAULT_SEGMENT_SIZE + (remainder > 0 ? 1 : 0);
			finalSegSize = remainder > 0 ? remainder : DEFAULT_SEGMENT_SIZE;
		}
		
		class Segment {
			long segno;
			// number of chunks in this segment
			int chunks;
			boolean isLast;
		
			Segment(long segno) {
				set(segno);
			}
		
			void set(long segno) {
				this.segno = segno;
				isLast = segno == maxSegs - 1;
				chunks = isLast ? finalSegSize : DEFAULT_SEGMENT_SIZE;
			}
			
			// compute size of chunk from seqno
			int size(long seqno) {
				return seqno == maxSeqs - 1 ? finalChunkSize : MAX_CHUNK_SIZE;
			}
		}
	}
	
	final class ReceivingSession extends Session {		
		ReceivingSegment seg;
		
		ReceivingSession(SocketAddress address, long id) {
			super(address, id, DEFAULT_SESSION_TIMEOUT);
		}
		
		ReceivingSession(SocketAddress address, long id, long timeout) {
			super(address, id, timeout);
		}

		@Override
		void process(byte cmd, DatagramPacket p) {
			// drop any stray delayed packets - shouldn't happen after single 
			// thread processing implemented
			if(state == COMPLETE || state == FAILED) return;
			switch(cmd){
			case SYN:
				syn(p);
				break;
			case DAT:
				dat(p);
				break;
			case RTM:
				rtm(p);
				break;
			}
		}
		
		// process incoming syn msg
		void syn(DatagramPacket p) {
			byte[] buf = p.getData();
			long len = bytesToUnsignedInt(buf, 6);
			// init session
			data = new byte[(int)len];
			length(len);
			seg = new ReceivingSegment(0);
			// ack
			int l = pack(ACK, buf);
			p.setLength(l);
			deliver(p);
			
			mark(false);
		}
		
		// process incoming dat msg
		void dat(DatagramPacket p) {
			if(seg == null) return;
			if(state == INIT) state = TRANSCEIVING;
			byte[] buf = p.getData();
			// sequence number
			long seqno = seqno(buf);
			// seqment number
			long segn = segno(seqno);
			// drop if not current or next segment - won't gt any after this seg but could 
			// pick up a delayed packet from a previous seg if the packet was retransmitted
			if(segn < segNo || segn > segNo + 1) return;
			if(segn == segNo + 1){
				// shouldn't get any packets unless we already sent a fin
				assert(seg.isComplete());
				
				segNo++;
				seg.set(segNo);
				seg.bits.clear();
			}
			
			// length of chunk - header is 10 bytes
			int l = p.getLength() - 10;
			// sanity check 
			assert(l == (segn == (maxSeqs - 1) ? finalChunkSize : MAX_CHUNK_SIZE));
			
			byte[] data = extract(buf, 10, l);
			
			boolean complete = seg.receive(seqno, data);
			if(complete){
				pack(FIN, segn, buf);
				deliver(p);
				if(segn == maxSegs - 1){
					// last segment complete
					completed();
					return;
				}
			}
			
			mark(false);
		}
		
		// send outgoing nak msg
		void nak() {
			DatagramPacket p = packet();
			byte[] buf = p.getData();
			int l = pack(NAK, segNo, seg.mask(), buf);
			p.setLength(l);
			deliver(p);
		}
		
		// process incoming ret msg - this seems redundant should dump it
		void rtm(DatagramPacket p) {
			dat(p);
		}
		
		@Override
		void timeout() {
			if(retry == maxRetries - 1){
				state = FAILED;
				fail();
				return;
			}
			
			DatagramPacket p = packet();
			byte[] buf = p.getData();
			int l = 0;
			
			switch(state){
			case INIT:
				l = pack(ACK, buf);
				break;
			case TRANSCEIVING:
				if(seg == null) return;
				l = seg.isComplete() ?
					pack(FIN, segNo, buf) :
					pack(NAK, segNo, seg.mask(), buf);
				break;
			default: return;
			}
		
			p.setLength(l);
			deliver(p);
			
			mark(true);
			retry++;
		}
		
		@Override
		void fail() {
			// nothing to do
			dispose();	
		}
		
		@Override
		void completed() {
			dispose();
			submit(() -> consumer.accept(address, data));
		}
		
		@Override
		void dispose() {
			submit(() -> {
				try{
					semaphore.acquire();
					receivers.remove(new Key(address, id));
				}catch(InterruptedException e){
				}finally{
					semaphore.release();
				}
			});
		}
		
		class ReceivingSegment extends Segment {
			final BitSet bits = new BitSet(8);
		
			ReceivingSegment(long segno) {
				super(segno);
			}
		
			boolean isComplete() {
				return bits.cardinality() == chunks;
			}
			
			boolean receive(long seqn, byte[] b) {
				int index = index(seqn, segNo);
				bits.set(index, true);
				int offset = (int)(seqn * MAX_CHUNK_SIZE);
				System.arraycopy(b, 0, data, offset, b.length);
				return bits.cardinality() == chunks;
			}		

			byte mask() {
				return bits.toByteArray()[0];
			}
		}
	}

	final class TransmittingSession extends Session {
		final CompletableFuture<Boolean> future;
		TransmittingSegment seg;
		
		TransmittingSession(SocketAddress address, long id, CompletableFuture<Boolean> future) {
			super(address, id, DEFAULT_SESSION_TIMEOUT);
			this.future = future;
		}
		
		TransmittingSession(SocketAddress address, long id, long timeout, CompletableFuture<Boolean> future) {
			super(address, id, timeout);
			this.future = future;
		}

		void transmit(byte[] data) {
			this.data = data;
			length(data.length);
			syn();
		}
		
		@Override
		void process(byte cmd, DatagramPacket p) {
			// drop any stray delayed packets - shouldn't happen after single 
			// thread processing implemented
			if(state == COMPLETE || state == FAILED) return;
			switch(cmd){
			case ACK:
				ack(p);
				break;
			case NAK:
				nak(p);
				break;
			case FIN:
				fin(p);
				break;
			}
		}
		
		// send outgoing syn msg
		void syn() {
			DatagramPacket p = packet();
			byte[] dst = p.getData();
			int l = pack(SYN, length, dst);
			p.setLength(l);
			deliver(p);
			
			mark(false);
		}
		
		// process incoming ack msg
		void ack(DatagramPacket p) {
			if(state== INIT){
				state = TRANSCEIVING;
				seg = new TransmittingSegment(0);
				seg.sendAll();
				
				mark(false);
			}
		}
		
		// process incoming fin msg
		void fin(DatagramPacket p) {
			if(state== TRANSCEIVING){
				byte[] buf = p.getData();
				long segno = bytesToUnsignedInt(buf, 6);
				if(segno == maxSegs - 1){	
					// completed transmitting message
					state = COMPLETE;
					completed();
				}else if(segno == segNo){
					// completed transmitting segment
					segNo++;
					seg.set(segNo);
					seg.sendAll();
					
					mark(false);
				}
			}
		}
		
		// process incoming nak msg
		void nak(DatagramPacket p) {
			if(state == TRANSCEIVING){
				byte[] buf = p.getData();
				long segn = bytesToUnsignedInt(buf, 6);
				byte mask = buf[10];
				// retransmit missing chunks
				seg.send(mask);
				
				mark(false);
			}
		}

		@Override
		void timeout() {
			if(retry == maxRetries - 1){
				state = FAILED;
				fail();
				return;
			}
			
			switch(state){
			case INIT:
				syn();
				break;
			case TRANSCEIVING:
				// seems heavy handed...
				seg.sendAll();
				break;
			default: return;
			}
			
			mark(true);
		}
		
		@Override
		void fail() {
			future.complete(false);
			dispose();
		}
		
		@Override
		void completed() {
			future.complete(true);
			dispose();
		}
		
		@Override
		void dispose() {
			submit(() -> {
				try{
					semaphore.acquire();
					transmitters.remove(new Key(address, id));
				}catch(InterruptedException e){
				}finally{
					semaphore.release();
				}
			});
		}
	
		class TransmittingSegment extends Segment {
			TransmittingSegment(long segno) {
				super(segno);
			}
		
			void send(byte mask) {
				byte a = 1;
				for(int i = 0; i < chunks; i++){
					if((a & mask) == a){
						send(i, true);
					}
					a *= 2;
				}
			}	
			
			void send(int index, boolean retransmit) {
				byte cmd = retransmit ? RTM : DAT;
				long seqno = seqno(segno, index);
				int offset = (int)(seqno * MAX_CHUNK_SIZE);
				// data length
				int len = size(seqno);
				DatagramPacket p = packet();
				byte[] dst = p.getData();
				// l = header len + data len
				int l = pack(cmd, seqno, data, offset, len, dst);
				p.setLength(l);
				deliver(p);
			}
			
			void sendAll() {
				for(int i = 0; i < chunks; i++){
					send(i, false);
				}
			}
		}
	}
	
	// session key
	static final class Key {
		final SocketAddress address;
		final long id;
		
		Key(SocketAddress address, long id) {
			this.address = address;
			this.id = id;
		}
		
		@Override
		public int hashCode() {      
			return (31 * ((31 * 17) + (int)id)) + address.hashCode(); 
		}
		
		@Override
		public boolean equals(Object o) {
			if(this == o) return true;
			if(o != null && o.getClass() == Key.class){
				Key k = (Key)o;
				return id == k.id && address.equals(k.address);
			}
			return false;
		}
	}
	
	enum State {INIT, TRANSCEIVING, COMPLETE, FAILED}
	
	private static final class SendRequest {
		final CompletableFuture<Boolean> future = new CompletableFuture<>();
		final SocketAddress address;
		final byte[] data;
		
		SendRequest(SocketAddress address, byte[] data) {
			this.address = address;
			this.data = data;
		}
	}
}
