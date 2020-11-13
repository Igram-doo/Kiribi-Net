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

import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import rs.igram.kiribi.net.stack.NetworkProtocol;

/**
 * 
 *
 * @author Michael Sargent
 */
public final class KAPProtocol {	
	public static final byte[] KA_DATA = {NetworkProtocol.KAP_PROTOCOL, 0};
	// server ports
	public static final int SERVER_PORT = 6732;
	public static final int KA_PORT = 6733;
	
	public static final SocketAddress SERVER_ADDRESS 
		= new InetSocketAddress(System.getProperty("server.address"), SERVER_PORT);
	// keep alive interval - 24 secounds
	public static final long KA_INTERVAL = 24*1000;	
	public static final DatagramPacket KA_PACKET = new DatagramPacket(KA_DATA, 2, SERVER_ADDRESS);
	
	private KAPProtocol() {}
}
