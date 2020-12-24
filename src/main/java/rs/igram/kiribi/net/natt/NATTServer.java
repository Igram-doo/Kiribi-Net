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

import java.net.DatagramPacket;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import rs.igram.kiribi.net.Address;

/**
 * Simple NATT server.
 *
 * @author Michael Sargent
 */
public final class NATTServer extends NATT {
	final Map<Address, SocketAddress> map = Collections.synchronizedMap(new HashMap<>());
		
	/**
	 * Instantiates a new <code>NATTServer</code> instance.
	 */
	public NATTServer() {}
	
	/**
	 * Evicts an address from this <code>NATTServer</code> instance.
	 *
	 * @param address The address to evict.
	 */
	public void evict(Address address) {
		map.remove(address);
	}
	
	@Override
	void process(DatagramPacket p) {
		byte[] buf = p.getData();
		byte protocol = protocol(buf); 
		// keepalive
		if(p.getLength() == 2 && protocol == KAP_PROTOCOL){
			write(p);
			return;
		}
		
		if(protocol != NATT_PROTOCOL) return;
		SocketAddress remote = p.getSocketAddress();		
		
		int c = buf[OFF_CMD]; // offset long session id + long natt id
	
		Address address = address(buf); //reg address or tunnel address
			
		switch(c){
		case REG: // registration request from remote peer
			map.put(address, remote);
			cmd(buf, ADR);
			inet(buf, remote);
			write(buf, remote);
			break;
		case CON: // tunnel request from remote peer
			SocketAddress dst = map.get(address);
			if(dst == null){
				// tunnel address not registered
				cmd(buf, ERR);
				write(buf, remote);
			}else{
				// notify dest of tunnel request
				cmd(buf, TUN);
				inet(buf, remote);
				write(buf, dst);
				// notify src of dst socket address
				cmd(buf, ADC);
				inet(buf, dst);
				write(buf, remote);
			}
			break;
		default: 
			break;
		}
	}
}
