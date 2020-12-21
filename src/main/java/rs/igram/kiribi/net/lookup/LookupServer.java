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
 
package rs.igram.kiribi.net.lookup;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeoutException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import rs.igram.kiribi.io.EncodableBytes;
import rs.igram.kiribi.io.VarInputStream;
import rs.igram.kiribi.io.VarOutputStream;
import rs.igram.kiribi.net.Address;
import rs.igram.kiribi.net.Endpoint;
import rs.igram.kiribi.net.EndpointProvider;
import rs.igram.kiribi.net.ServerEndpoint;

import static rs.igram.kiribi.net.stack.lookup.LookupProtocol.*;

/**
 * 
 *
 * @author Michael Sargent
 */
public final class LookupServer {
	private final EndpointProvider<SocketAddress> provider;
	private final Map<Address, InetSocketAddress> cache = Collections.synchronizedMap(new HashMap<Address, InetSocketAddress>());
	
	private ServerEndpoint server;
	
	public LookupServer(EndpointProvider<SocketAddress> provider) {
		this.provider = provider;
	}
	
	public void listen()  throws IOException, InterruptedException, TimeoutException {
		server = provider.server();
		server.accept(this::accept);
	}
	
	private void accept(Endpoint endpoint) {
		try{
			EncodableBytes request = endpoint.read(EncodableBytes::new);
		
			VarInputStream in = new VarInputStream(request.bytes());
			byte b = in.readByte();
			Address address = in.read(Address::new);
			InetSocketAddress socketAddress = null;
			
			switch(b) {
			case REGISTER:
				socketAddress = (InetSocketAddress)endpoint.remote();
				cache.put(address, socketAddress);
				endpoint.write(ack());
				break;
			case UNREGISTER:
				cache.remove(address);
				endpoint.write(ack());
				break;
			case LOOKUP:
				socketAddress = cache.get(address);
				endpoint.write(response(socketAddress));
				break;
			}	
		} catch(IOException e) {
			// to do
		}
	}
	
	private static EncodableBytes ack() throws IOException {
		return new EncodableBytes(new byte[]{ACK});
	}
	
	private static EncodableBytes response(InetSocketAddress socketAddress) throws IOException {
		VarOutputStream out = new VarOutputStream();
		if (socketAddress == null) {
			out.write(UNKNOWN);
		} else {
			out.write(ACK);
			out.writeAddress(socketAddress);
		}
		return new EncodableBytes(out.toByteArray());
	}
}