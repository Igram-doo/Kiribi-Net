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
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

import rs.igram.kiribi.net.Address;
import rs.igram.kiribi.net.NetworkExecutor;
import rs.igram.kiribi.net.stack.kap.KAPProcessor;
import rs.igram.kiribi.net.stack.natt.NATTProcessor;
import rs.igram.kiribi.net.stack.natt.NATTProtocol;
import rs.igram.kiribi.net.stack.rmp.RMPProcessor;

import static rs.igram.kiribi.io.ByteUtils.*;

/**
 * 
 *
 * @author Michael Sargent
 */
public final class DatagramIPV4Stack extends DatagramStack {
	
	Consumer<SocketAddress> onIncoming;
	Consumer<Set<SocketAddress>> onExpired;
	Consumer<NATTProcessor.SessionEvent> listener;
	NATTProcessor natt;
	 
	public DatagramIPV4Stack(NetworkExecutor executor, Address address, SocketAddress serverAddress, 
		InetSocketAddress socketAddress, BiConsumer<SocketAddress,byte[]> consumer, 
		Consumer<SocketAddress> onIncoming, Consumer<Set<SocketAddress>> onExpired) {
		super(executor, address, serverAddress, StandardProtocolFamily.INET, socketAddress, consumer);
	}

	@Override
	public void configure() {
		rmp = new RMPProcessor(consumer);
		
		listener = e -> {
			if(mux != null && e.type == NATTProtocol.SessionType.SOCKET){ 
				try{
					rmp.process(e.data);
				}catch(Exception x){
					// ignore
				}
			}
		};
		
		natt = new NATTProcessor(serverAddress, listener);
		
		
		kap = new KAPProcessor(onIncoming, onExpired);
		mux.register(natt, rmp, kap);	
	}
	
	@Override
	public SocketAddress connect(Address address) throws IOException {
		rs.igram.kiribi.net.stack.natt.NATTProcessor.Key key = natt.connect(address);
		return key == null ? null : key.address;
	}                               
	
	@Override
	protected void register(Address address, InetSocketAddress inet) throws IOException {
		natt.register(address);
	}
}