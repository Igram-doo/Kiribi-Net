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
 
package rs.igram.kiribi.net.stack.lookup;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import rs.igram.kiribi.io.EncodableBytes;
import rs.igram.kiribi.io.VarInputStream;
import rs.igram.kiribi.io.VarOutputStream;
import rs.igram.kiribi.net.Address;
import rs.igram.kiribi.net.Endpoint;
import rs.igram.kiribi.net.TCPEndpointFactory;

import static rs.igram.kiribi.net.stack.lookup.LookupProtocol.*;

/**
 * 
 *
 * @author Michael Sargent
 */
public final class Lookup {
	private final Address address;
	private final InetSocketAddress socketAddress;
	private final InetSocketAddress lookupServerAddress;
	
	public Lookup(Address address, InetSocketAddress socketAddress, InetSocketAddress lookupServerAddress) {
		this.address = address;
		this.socketAddress = socketAddress;
		this.lookupServerAddress = lookupServerAddress;
	}
	
	public void register() throws IOException, InterruptedException {
		VarOutputStream out = new VarOutputStream();
		out.write(REGISTER);
		out.write(address);
		out.writeAddress(socketAddress);
		EncodableBytes bytes = new EncodableBytes(out.toByteArray());
		Endpoint endpoint = open();
		endpoint.write(bytes);
		
		EncodableBytes response = endpoint.read(EncodableBytes::new);
		endpoint.close();
		
		VarInputStream in = new VarInputStream(response.bytes());
		byte b = in.readByte();
		if (b != ACK) {
			throw new IOException(in.readUTF());
		}		
	}
	
	public void unregister() throws IOException, InterruptedException {
		VarOutputStream out = new VarOutputStream();
		out.write(UNREGISTER);
		out.write(address);
		EncodableBytes bytes = new EncodableBytes(out.toByteArray());
		Endpoint endpoint = open();
		endpoint.write(bytes);
		EncodableBytes response = endpoint.read(EncodableBytes::new);
		endpoint.close();
		
		VarInputStream in = new VarInputStream(response.bytes());
		byte b = in.readByte();
		if (b != ACK) {
			throw new IOException(in.readUTF());
		}
	}
	
	public SocketAddress lookup(Address address) throws IOException, InterruptedException {
		VarOutputStream out = new VarOutputStream();
		out.write(LOOKUP);
		out.write(address);
		EncodableBytes bytes = new EncodableBytes(out.toByteArray());
		Endpoint endpoint = open();
		endpoint.write(bytes);
		EncodableBytes response = endpoint.read(EncodableBytes::new);
		endpoint.close();
		
		VarInputStream in = new VarInputStream(response.bytes());
		byte b = in.readByte();
		
		switch(b){
		case ACK: return in.readAddress();
		case UNKNOWN: return null;
		case ERROR: throw new IOException(in.readUTF());
		}
		// make compiler happy
		return null;
	}
		
	private Endpoint open() throws IOException, InterruptedException {
		try{
			return TCPEndpointFactory.open(lookupServerAddress);
		} catch(ExecutionException e) {
			throw new IOException(e);
		}
	}	
}