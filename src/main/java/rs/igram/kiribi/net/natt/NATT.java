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
 
package rs.igram.kiribi.net.natt;

import java.io.*;
import java.io.Console;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.*;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.*;
import java.util.Enumeration;
import java.util.*;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import rs.igram.kiribi.net.Address;
import rs.igram.kiribi.net.NetworkExecutor;

import static rs.igram.kiribi.io.ByteUtils.*;

/**
 * Support class providing methods for a NATT server.
 *
 * @author Michael Sargent
 */
abstract class NATT {
	private static final Logger LOGGER = Logger.getLogger(NATT.class.getName());
	
	static final byte NATT_PROTOCOL = 1; // natt protocol
	static final byte KAP_PROTOCOL	= 2; // keep alive protocol
	static final byte RMP_PROTOCOL	= 3; // rmp protocol
	
	static final byte[] KA_DATA = {KAP_PROTOCOL, 0};
	
	static final byte REG = 1; // register - kiribi address
	static final byte CON = 2; // connection request - kiribi address
	static final byte TUN = 3; // tunnel request - remote socket address
	static final byte ADR = 4; // register response - remote socket address
	static final byte ADC = 5; // connection response - remote socket address
	static final byte ERR = 7;
	
	static final byte SYN = 1;
	static final byte ACK = 2;
	static final byte FIN = 3;

	// offsets
	static int OFF_ID	= 1;
	static int OFF_CMD 	= 9;
	static int OFF_DATA	= 10;
	
	// server ports
	/** Default server port. */
	public static final int SERVER_PORT = 6732;
	static final int KA_PORT = 6733;
	
	/*
	static final SocketAddress SERVER_ADDRESS ;
	static{
		try{
			SERVER_ADDRESS = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), SERVER_PORT);	
		}catch(Exception e){
			throw new RuntimeException("Couldn't set server address", e);
		}
	}
	*/
	static final long KA_INTERVAL = 24*1000;	
	//static final DatagramPacket KA_PACKET = new DatagramPacket(KA_DATA, 2, SERVER_ADDRESS);
	static final int PACKET_SIZE = 1472;
	// linux - has the smallest default max buffer size
	// to change on linux: sysctl -w net.core.rmem_max=26214400
	static final int MAX_UDP_BUF_SIZE = 131071;
	
	DatagramSocket socket;
	Future<?> reader;
	
	private static final String OS = System.getProperty("os.name", "unknown");
	
	/**
	 * Instantiates a new <code>NATT</code> instance.
	 */
	 protected NATT() {}
	 
	/*
	public void start(int port) {
		start(internal(), port);
	}
	*/
	
	/**
	 * Starts this <code>NATT</code> instance.
	 *
	 * @param addr The inet address to listen on.
	 * @param port The port to listen on.
	 */
	public void start(InetAddress addr, int port) {
		NetworkExecutor executor = new NetworkExecutor();
		executor.onShutdown(6, this::shutdown);
		try{
			socket = new DatagramSocket(new InetSocketAddress(addr, port));
			socket.setReceiveBufferSize(MAX_UDP_BUF_SIZE);
			socket.setSendBufferSize(MAX_UDP_BUF_SIZE);
			
			reader = executor.submit(this::read);
		}catch(Throwable e){
			e.printStackTrace();
		}
	}

	abstract void process(DatagramPacket p);
	
	void read() {
		while(!Thread.currentThread().isInterrupted()){
			byte[] buf = new byte[PACKET_SIZE];
			DatagramPacket p = new DatagramPacket(buf, PACKET_SIZE);
			try{
				socket.receive(p);
				process(p);
			}catch(SocketException e){
				System.out.println("Socket closed");
				break;
			}catch(IOException e){
				e.printStackTrace();
			}
		}
	}
	
	void write(DatagramPacket p){
		try{
			socket.send(p);
		}catch(IOException e){
			e.printStackTrace();
		}
	}

	void write(byte[] buf, SocketAddress address){
		try{
			socket.send(new DatagramPacket(buf, buf.length, address));
		}catch(IOException e){
			e.printStackTrace();
		}
	}

	static void id(byte[] b, long id) {
		put(b, OFF_ID, id);
	}
	
	static void cmd(byte[] b, byte cmd) {
		b[OFF_CMD] = cmd;
	}
	
	static void protocol(byte[] b, byte protocol) {
		b[0] = protocol;
	}
	
	static void address(byte[] b, Address address) {
		System.arraycopy(address.encodeUnchecked(), 0, b, OFF_DATA, 20);
	}
	
	static void inet(byte[] b, SocketAddress address) throws Exception {
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

	static long id(byte[] b) {
		return getLong(b, OFF_ID);
	}
	
	static byte cmd(byte[] b) {
		return b[OFF_CMD];
	}
	
	static byte protocol(byte[] b) {
		return b[0];
	}
	
	static Address address(byte[] b) {
		return new Address(extract(b, OFF_DATA, 20));
	}
	
	static SocketAddress inet(byte[] src) throws Exception {
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
   
	static InetAddress internal() {
		try{
			for(Enumeration<NetworkInterface> e1 = NetworkInterface.getNetworkInterfaces(); e1.hasMoreElements();){
				NetworkInterface i = e1.nextElement();
				 if(!i.isLoopback() && i.isUp() && !i.toString().contains("Teredo") && !i.isVirtual()){
					for(Enumeration<InetAddress> e2 = i.getInetAddresses(); e2.hasMoreElements();){
						InetAddress a = e2.nextElement();
						if(a instanceof Inet4Address && !a.isLinkLocalAddress()){
							return a;
						}
					}
				}
			}			
		}catch(Exception e){
			// ignore
		}
		
		return null;
	}
	
	static void print(byte[] buf, SocketAddress address) {}
		
	/**
	 * Shuts down this <code>NATT</code> instance.
	 */
	public void shutdown() {
		try{
			if(reader != null) reader.cancel(true);
			if(socket != null) socket.close();
		}catch(Throwable e) {
			// ignore
		}
	}
}
