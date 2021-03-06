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
import rs.igram.kiribi.net.lookup.LookupServer;
import rs.igram.kiribi.net.stack.discovery.*;
import rs.igram.kiribi.net.stack.lookup.*;

/**
 * 
 *
 * @author Michael Sargent
 */
public class EndpointProviderTest {
	
	@Test
	public void testDiscovery() throws IOException, InterruptedException, Exception {
		// discovery1
		var key1 = KeyPairGenerator.generateKeyPair().getPublic();
		var address1 = new Address(key1);
		var socketAddress1 = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 6888);
		var group1 = new InetSocketAddress(InetAddress.getByName("233.0.0.0"), 4769);	
		var discovery1 = new Discovery(address1, socketAddress1, group1);		
   	    discovery1.start();
   	    
   	    Thread.sleep(500);
   	    
   	    // discovery2
   	    var key2 = KeyPairGenerator.generateKeyPair().getPublic();
		var address2 = new Address(key2);
		var socketAddress2 = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 6889);
		var group2 = new InetSocketAddress(InetAddress.getByName("233.0.0.0"), 4769);		
		var discovery2 = new Discovery(address2, socketAddress2, group2);		
   	    discovery2.start();
   	    
   	    Thread.sleep(500);
		
   	    InetSocketAddress result = null;
   	    
   	    result = discovery1.address(address1);
		assertEquals(socketAddress1, result);		
		result = discovery1.address(address2);
		assertEquals(socketAddress2, result);
		
		result = discovery2.address(address1);
		assertEquals(socketAddress1, result);
		result = discovery2.address(address2);
		assertEquals(socketAddress2, result);
		
		discovery1.shutdown();
		discovery2.shutdown();
	}
	
	@Test
	public void testLookup() throws IOException, InterruptedException, Exception {
		// lookup server
		var port1 = 6730;
		var lookupAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port1);
   	    var server = new LookupServer();
   	    server.start(lookupAddress);
   	    
   	    //lookup
   	    var key = KeyPairGenerator.generateKeyPair().getPublic();
		var address = new Address(key);
   	    var port2 = 6731;
		var socketAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port2);
		var lookup = new Lookup(address, socketAddress, lookupAddress);
		
		assertNull(lookup.lookup(address));
		
		lookup.register();
		assertEquals(socketAddress, lookup.lookup(address));
		
		lookup.unregister();
		assertNull(lookup.lookup(address));
	}
	
	@Test
	public void testTCP() throws IOException, InterruptedException, Exception {
		var port = 6732;
		var key = KeyPairGenerator.generateKeyPair().getPublic();
		var address = new Address(key);
		var serverAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), LookupServer.SERVER_PORT);
		var socketAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port);
		var mapper = AddressMapper.lookup(address, socketAddress, serverAddress);
		var provider = EndpointProvider.tcp(mapper);
		var connectionAddress = new ConnectionAddress(address);
		
		var server = new LookupServer();
   	   	server.start(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), LookupServer.SERVER_PORT));
   	   	
   	   	
		var test = new ProviderTest(provider, connectionAddress);
		test.run(6732);
   	   
		assertTrue(test.openSuccess);
		assertTrue(test.readSuccess);
		assertTrue(test.writeSuccess);
	}
	
	@Test
	public void testUDPSameAddress() throws IOException, InterruptedException, Exception {
		var port = 6733;
		var key = KeyPairGenerator.generateKeyPair().getPublic();
		var address = new Address(key);
		var serverAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), NATTServer.SERVER_PORT);
		var socketAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port);
		var provider = EndpointProvider.udp(socketAddress, address, serverAddress);		
		var connectionAddress = new ConnectionAddress(address);
		
		var server = new NATTServer();
   	   	server.start(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), NATTServer.SERVER_PORT));
   	   	
		var test = new ProviderTest(provider, connectionAddress);
		test.run(port);
   	   
		assertTrue(test.openSuccess);
		assertTrue(test.readSuccess);
		assertTrue(test.writeSuccess);
		
		server.shutdown();
		
	}
	
	@Test
	public void testUDPDifferentAddresses() throws IOException, InterruptedException, Exception {
		var serverPort = NATTServer.SERVER_PORT + 1;
		var port1 = 4733;
		var port2 = 4734;
		var key = KeyPairGenerator.generateKeyPair().getPublic();
		var address1 = new Address(key);
		key = KeyPairGenerator.generateKeyPair().getPublic();
		var address2 = new Address(key);
		
		var serverAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), serverPort);
		var socketAddress1 = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port1);
		var socketAddress2 = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port2);
		var provider1 = EndpointProvider.udp(socketAddress1, address1, serverAddress);		
		var connectionAddress1 = new ConnectionAddress(address1);
		
		var provider2 = EndpointProvider.udp(socketAddress2, address2, serverAddress);		
		var connectionAddress2 = new ConnectionAddress(address2);
		
		var server = new NATTServer();
   	   	server.start(serverAddress);
   	   	
		var test = new ProviderTest2(provider1, connectionAddress1, provider2, connectionAddress2);
		test.run(port1, port2);
   	   
		assertTrue(test.openSuccess);
		assertTrue(test.readSuccess);
		assertTrue(test.writeSuccess);
		
		server.shutdown();
	}
	
	@Test
	public void testLAN() throws IOException, InterruptedException, Exception {
		var port = 6733;
		var key = KeyPairGenerator.generateKeyPair().getPublic();
		var address = new Address(key);
		var group = EndpointProvider.defaultGroup();
		var socketAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port);
		var mapper = AddressMapper.discovery(address, socketAddress, group);
		var provider = EndpointProvider.tcp(mapper);	
		var connectionAddress = new ConnectionAddress(address);
		
		var test = new ProviderTest(provider, connectionAddress);
		test.run(port);
   	   
		assertTrue(test.openSuccess);
		assertTrue(test.readSuccess);
		assertTrue(test.writeSuccess);
	}
   
	private static class ProviderTest {
   	   boolean openSuccess = false;
   	   boolean readSuccess = false;
   	   boolean writeSuccess = false;
   	     	   
   	   Message msg1 = new Message("a");
   	   Message msg2 = new Message("b");
   	   	   
   	   CountDownLatch availableSignal = new CountDownLatch(1);
   	   CountDownLatch openSignal = new CountDownLatch(1);
   	   
   	   NetworkExecutor executor = new NetworkExecutor();
   	   EndpointProvider provider;
   	   ConnectionAddress address;
   	   
   	   ProviderTest(EndpointProvider provider, ConnectionAddress address) {
   	   	   this.provider = provider;
   	   	   this.address = address;
   	   }
   	   
   	   void run(int port) throws IOException, InterruptedException, Exception {
   	   	   availableSignal.countDown();
   	   	   availableSignal.await(3, TimeUnit.SECONDS);
   	   	   var se = provider.server();
   	   	   se.accept(this::accept);
   	   	   
   	   	   var e = provider.open(address);
   	   	   e.write(msg1);
   	   	   var msg = e.read(Message::new);
   	   	   writeSuccess = msg2.equals(msg);
   	   	   
   	   	   openSignal.await(3, TimeUnit.SECONDS);
   	   	    	   	   
   	   }
   	   
   	   void accept(Endpoint e) {
   	   	   executor.submit(() -> {
   	   	   		openSuccess = true;
   	   	   		
   	   	   		try {
   	   	   			var msg = e.read(Message::new);
   	   	   			readSuccess = msg1.equals(msg);
   	   	   			e.write(msg2);
   	   	   		} catch(Exception ex) {
   	   	   			ex.printStackTrace();
   	   	   		} finally {
   	   	   			openSignal.countDown();
   	   	   		}
   	   	   });
   	   }
   }
   
	private static class ProviderTest2 {
   	   boolean openSuccess = false;
   	   boolean readSuccess = false;
   	   boolean writeSuccess = false;
   	     	   
   	   Message msg1 = new Message("a");
   	   Message msg2 = new Message("b");
   	   	   
   	   CountDownLatch availableSignal = new CountDownLatch(1);
   	   CountDownLatch openSignal = new CountDownLatch(1);
   	   
   	   NetworkExecutor executor = new NetworkExecutor();
   	   EndpointProvider provider1;
   	   ConnectionAddress address1;
   	   EndpointProvider provider2;
   	   ConnectionAddress address2;
   	   
   	   ProviderTest2(
   	   	   EndpointProvider provider1, ConnectionAddress address1,
   	   	   EndpointProvider provider2, ConnectionAddress address2) {
   	   
   	   	   this.provider1 = provider1;
   	   	   this.address1 = address1;
   	   	   this.provider2 = provider2;
   	   	   this.address2 = address2;
   	   }
   	   
   	   void run(int port1, int port2) throws IOException, InterruptedException, Exception {
   	   	   availableSignal.countDown();
   	   	   availableSignal.await(3, TimeUnit.SECONDS);
   	   	   
   	   	   var se = provider1.server();
   	   	   se.accept(this::accept);
   	   	   
   	   	   var e = provider2.open(address1);
   	   	   e.write(msg1);
   	   	   var msg = e.read(Message::new);
   	   	   writeSuccess = msg2.equals(msg);
   	   	   
   	   	   openSignal.await(3, TimeUnit.SECONDS);
   	   	    	   	   
   	   }
   	   
   	   void accept(Endpoint e) {
   	   	   executor.submit(() -> {
   	   	   		openSuccess = true;
   	   	   		
   	   	   		try {
   	   	   			var msg = e.read(Message::new);
   	   	   			readSuccess = msg1.equals(msg);
   	   	   			e.write(msg2);
   	   	   		} catch(Exception ex) {
   	   	   			ex.printStackTrace();
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
   	   	   var t = (Message)o;
   	   	   return txt.equals(t.txt);
   	   }
   }
}