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
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeoutException;

import rs.igram.kiribi.net.stack.discovery.Discovery;
/**
 * 
 *
 * @author Michael Sargent
 */
final class LANEndpointProvider extends EndpointProvider {
	final Discovery discovery;
	private ServerEndpoint server;
	
	public LANEndpointProvider(NetworkExecutor executor, InetSocketAddress socketAddress, Address address, InetSocketAddress group) {
		super(socketAddress, address);
		
		discovery = new Discovery(executor, address, socketAddress, group);		
	}

	@Override
	public Endpoint open(ConnectionAddress address)
		throws IOException, InterruptedException {

		discovery.start();
		InetSocketAddress remoteAddress = discovery.address(address.address);
		if (remoteAddress == null) return null;
		try{
			return TCPEndpointFactory.open(remoteAddress);
		}catch(Exception e){
			discovery.remove(address.address);
			throw new IOException(e);
		}
	}

	@Override
	public ServerEndpoint server() 
		throws IOException, InterruptedException, TimeoutException {
			
		if (server == null || !server.isOpen())  {	
			discovery.start();
			server =  TCPEndpointFactory.server(socketAddress);
		}
		
		return server;
	}
}
