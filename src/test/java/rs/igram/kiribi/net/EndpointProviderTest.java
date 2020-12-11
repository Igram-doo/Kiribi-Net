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
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rs.igram.kiribi.crypto.EC25519PrivateKey;
import rs.igram.kiribi.crypto.EC25519PublicKey;
import rs.igram.kiribi.crypto.KeyPairGenerator;
import rs.igram.kiribi.io.*;
import rs.igram.kiribi.net.natt.NATTServer;

/**
 * 
 *
 * @author Michael Sargent
 */
public class EndpointProviderTest {
	
	@Test
	public void testTCP() throws IOException, InterruptedException, Exception {
		int port = 6732;
		NetworkExecutor executor = new NetworkExecutor();
		SocketAddress address = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port);
		EndpointProvider<SocketAddress> provider = EndpointProvider.tcpProvider(executor);
		ProviderTest test = new ProviderTest<SocketAddress>(executor, provider, address);
		test.run(6732);
   	   
		assertTrue(test.openSuccess);
		assertTrue(test.readSuccess);
		assertTrue(test.writeSuccess);
	}
	
	@Test
	public void testUDP() throws IOException, InterruptedException, Exception {
		int port = 6733;
		NetworkExecutor executor = new NetworkExecutor();
		PublicKey key = KeyPairGenerator.generateKeyPair().getPublic();
//		Address address = new Address(((EC25519PublicKey)key).hash());
		Address address = new Address(key);
		SocketAddress serverAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), NATTServer.SERVER_PORT);
		EndpointProvider<ConnectionAddress> provider = EndpointProvider.udpProvider(executor, address, serverAddress);		
		ConnectionAddress connectionAddress = new ConnectionAddress(address, 1l);
		
		NATTServer server = new NATTServer();
   	   	server.start(InetAddress.getByName("127.0.0.1"), NATTServer.SERVER_PORT);
   	   	
		ProviderTest test = new ProviderTest<ConnectionAddress>(executor, provider, connectionAddress);
		test.run(port);
   	   
		assertTrue(test.openSuccess);
		assertTrue(test.readSuccess);
		assertTrue(test.writeSuccess);
		
		server.shutdown();
	}
   
	private static class ProviderTest<A> {
   	   boolean openSuccess = false;
   	   boolean readSuccess = false;
   	   boolean writeSuccess = false;
   	     	   
   	   Message msg1 = new Message("a");
   	   Message msg2 = new Message("b");
   	   	   
   	   CountDownLatch availableSignal = new CountDownLatch(1);
   	   CountDownLatch openSignal = new CountDownLatch(1);
   	   
   	   NetworkExecutor executor;
   	   EndpointProvider<A> provider;
   	   A address;
   	   
   	   ProviderTest( NetworkExecutor executor, EndpointProvider<A> provider, A address) {
   	   	   this.executor = executor;
   	   	   this.provider = provider;
   	   	   this.address = address;
   	   }
   	   
   	   void run(int port) throws IOException, InterruptedException, Exception {
   	   	//   NetworkMonitor.monitor(executor);
   	   	//   NetworkMonitor.onAvailable(() -> {
   	   		   availableSignal.countDown();
   	   	//   });
   	   	   availableSignal.await(3, TimeUnit.SECONDS);
   	   	   
   	   	   ServerEndpoint se = provider.open(port);
   	   	   se.accept(this::accept);
   	   	   
   	   	   Endpoint e = provider.open(address);
   	   	   e.write(msg1);
   	   	   Message msg = e.read(Message::new);
   	   	   writeSuccess = msg2.equals(msg);
   	   	   
   	   	   openSignal.await(3, TimeUnit.SECONDS);
   	   	   
   	   	   //executor.shutdown();  	   	   
   	   }
   	   
   	   void accept(Endpoint e) {
   	   	   executor.submit(() -> {
   	   	   		openSuccess = true;
   	   	   		
   	   	   		try {
   	   	   			Message msg = e.read(Message::new);
   	   	   			readSuccess = msg1.equals(msg);
   	   	   			e.write(msg2);
   	   	   		} catch(Exception ex) {
   	   	   			
   	   	   		} finally {
   	   	   			openSignal.countDown();
   	   	   		}
   	   	   });
   	   }
   }
   
   static class Message implements Encodable {
   	   private String txt;
   	   
   	   Message(String txt) {
   	   	   this.txt = txt;
   	   }
   	   
   	   Message(VarInput in) throws IOException {
   	   	   txt = in.readUTF();
   	   }
   	   
   	   @Override
   	   public void write(VarOutput out) throws IOException {
   	   	   out.writeUTF(txt);
   	   }
   	   
   	   @Override
   	   public boolean equals(Object o) {
   	   	   if(o == null || !(o instanceof Message)) return false;
   	   	   Message t = (Message)o;
   	   	   return txt.equals(t.txt);
   	   }
   }
}