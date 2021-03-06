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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Factory for endpoints.
 *
 * @author Michael Sargent
 */
public abstract class EndpointProvider {
	final Map<Address,SocketAddress> cache = new HashMap<>();
	
	/** The address associated with this endpoint provider. */
	public final Address address;
	
	/** The socket address associated with this endpoint provider. */
	public final InetSocketAddress socketAddress;
	
	EndpointProvider(InetSocketAddress socketAddress, Address address) {
		this.socketAddress = socketAddress;
		this.address = address;
	}
		
	/**
	 * Returns a udp endpoint provider.
	 * 
	 * @param socketAddress The socket address this endpoint provider will use.
	 * @param address The address the returned endpoint provider will use.
	 * @param nattAddress The socket address of the NATT server the returned endpoint provider will use.
	 * @return Returns a udp endpoint provider.
	 */
	public static EndpointProvider udp(InetSocketAddress socketAddress, Address address, InetSocketAddress nattAddress) {
		return new UDPEndpointProvider(socketAddress, address, nattAddress);
	}
		
	/**
	 * Returns a tcp endpoint provider.
	 * 
	 * @param mapper The address mapper this endpoint provider will use.
	 * @return Returns a tcp endpoint provider.
	 */
	public static EndpointProvider tcp(AddressMapper mapper) {
		return new TCPEndpointProvider(mapper);
	}

	/**
	 * Returns the default multicast group address.
	 *
	 * @return Returns the default multicast group address.
	 * @throws UnknownHostException if there was a problem getting the default multicast group address.
	 */
	public static InetSocketAddress defaultGroup() throws UnknownHostException {
		return new InetSocketAddress(InetAddress.getByName("233.0.0.0"), 4767);
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
	public abstract Endpoint open(ConnectionAddress address) 
		throws IOException, InterruptedException;
		
	/**
	 * Shuts down this endpoint provider.
	 */
	public void shutdown() {}
}
