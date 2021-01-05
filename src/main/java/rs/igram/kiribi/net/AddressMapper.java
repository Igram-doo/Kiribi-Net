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
import java.util.Map;

import rs.igram.kiribi.net.stack.discovery.Discovery;
import rs.igram.kiribi.net.stack.lookup.Lookup;

/**
 * An instance of this class provides a mapping between addresses and InetSocketAddresses.
 *
 * @author Michael Sargent
 */
public abstract class AddressMapper {
	/** The <code>Address</code> associated with this mapper. */
	public final Address address;
	/** The <code>InetSocketAddress</code> associated with this mapper. */
	public final InetSocketAddress socketAddress;

	/**
	 * Constructor.
	 *
	 * @param address The <code>Address</code> associated with this mapper.
	 * @param socketAddress The <code>InetSocketAddress</code> associated with this mapper.
	 */	
	protected AddressMapper(Address address, InetSocketAddress socketAddress) {
		this.address = address;
		this.socketAddress = socketAddress;
	}
	
	/**
	 * Initialize this mapper.
	 *
	 * @throws IOException if there was a io problem during initializing the mapper.
	 */	
	public void init() throws IOException {}
	
	/**
	 * Close this mapper and release any resources it might hold.
	 */	
	public void close() {}
	
	/**
	 * Registers the address and inet socket associated with this mapper.
	 *
	 * @throws IOException if there was a io problem during registration.
	 * @throws InterruptedException if this method was interrupted.
	 */		
	public void register() throws IOException, InterruptedException {}
		
	/**
	 * Unregisters the address and inet socket associated with this mapper.
	 *
	 * @throws IOException if there was a io problem during registration.
	 * @throws InterruptedException if this method was interrupted.
	 */		
	public void unregister() throws IOException, InterruptedException {}
	
	/**
	 * Retrieves the <code>InetSocketAddress</code> associated with the given <code>Address</code>.
	 *
	 * @param address The <code>Address</code> to lookup
	 * @return The associated <code>InetSocketAddress</code> or <code>null</code> if not found.
	 * @throws IOException if there was a io problem during registration.
	 * @throws InterruptedException if this method was interrupted.
	 */			
	public abstract InetSocketAddress lookup(Address address) throws IOException, InterruptedException;
	
	/**
	 * Returns an <code>AddressMapper</code> which relies on a lookup server.
	 *
	 * @param address The <code>Address</code> associated with this mapper.
	 * @param socketAddress The <code>InetSocketAddress</code> associated with this mapper.
	 * @param serverAddress The <code>InetSocketAddress</code> of the lookup server associated with this mapper.
	 * @return An <code>AddressMapper</code> which relies on a lookup server.
	 */	
	public static AddressMapper lookup(Address address, InetSocketAddress socketAddress, InetSocketAddress serverAddress) {
		return new LookupMapper(address, socketAddress, serverAddress);
	}
	
	/**
	 * Returns an <code>AddressMapper</code> which relies on multicast discovery.
	 *
	 * @param address The <code>Address</code> associated with this mapper.
	 * @param socketAddress The <code>InetSocketAddress</code> associated with this mapper.
	 * @param multicastAddress The <code>InetSocketAddress</code> of the multicast discovery address.
	 * @return An <code>AddressMapper</code> which relies on multicast discovery.
	 */	
	public static AddressMapper discovery(Address address, InetSocketAddress socketAddress, InetSocketAddress multicastAddress) {
		return new DiscoveryMapper(address, socketAddress, multicastAddress);
	}
	
	/**
	 * Returns an <code>AddressMapper</code> which relies on a map.
	 *
	 * @param address The <code>Address</code> associated with this mapper.
	 * @param socketAddress The <code>InetSocketAddress</code> associated with this mapper.
	 * @param map The <code>Map</code> of the lookup server associated with this mapper.
	 * @return An <code>AddressMapper</code> which relies on a map.
	 */	
	public static AddressMapper map(Address address, InetSocketAddress socketAddress, Map<Address, InetSocketAddress> map) {
		return new StaticMapper(address, socketAddress, map);
	}
	
	/**
	 * A address mapper which delegates to a Lookup server.
	 */	
	private static class LookupMapper extends AddressMapper {
		/** The <code>Lookup</code> associated with this mapper. */
		public final Lookup lookup;
		
		/**
		 * Instantiates a <code>LookupMapper</code> instance.
		 *
		 * @param address The <code>Address</code> associated with this mapper.
		 * @param socketAddress The <code>InetSocketAddress</code> associated with this mapper.
		 * @param serverAddress The <code>InetSocketAddress</code> of the lookup server associated with this mapper.
		 */	
		public LookupMapper(Address address, InetSocketAddress socketAddress, InetSocketAddress serverAddress) {
			super(address, socketAddress);
			
			lookup = new Lookup(address, socketAddress, serverAddress);
		}
	
		@Override
		public void register() throws IOException, InterruptedException, InterruptedException {
			lookup.register();
		}
	
		@Override
		public void unregister() throws IOException, InterruptedException, InterruptedException {
			lookup.unregister();
		}
		
		@Override
		public InetSocketAddress lookup(Address address) throws IOException, InterruptedException {
			return lookup.lookup(address);
		}
	}
	
	/**
	 * A address mapper which uses multicast discovery.
	 */	
	private static class DiscoveryMapper extends AddressMapper {
		/** The <code>Lookup</code> associated with this mapper. */
		public final Discovery discovery;
		
		/**
		 * Instantiates a <code>LookupMapper</code> instance.
		 *
		 * @param address The <code>Address</code> associated with this mapper.
		 * @param socketAddress The <code>InetSocketAddress</code> associated with this mapper.
		 * @param multicastAddress The <code>InetSocketAddress</code> of the multicast discovery address.
		 */	
		public DiscoveryMapper(Address address, InetSocketAddress socketAddress, InetSocketAddress multicastAddress) {
			super(address, socketAddress);
			
			discovery = new Discovery(address, socketAddress, multicastAddress);
		}
	
		@Override
		public void init() throws IOException {
			discovery.start();
		}
	
		@Override
		public void close() {
			discovery.shutdown();
		}
		
		@Override
		public InetSocketAddress lookup(Address address) throws IOException, InterruptedException {
			return discovery.address(address);
		}
	}
	
	/**
	 * A address mapper which lookups entries from a <code>Map</code>.
	 */	
	private static class StaticMapper extends AddressMapper {
		/** The <code>Map</code> associated with this mapper. */
		public final Map<Address, InetSocketAddress> map;
		
		/**
		 * Instantiates a <code>StaticMapper</code> instance.
		 *
		 * @param address The <code>Address</code> associated with this mapper.
		 * @param socketAddress The <code>InetSocketAddress</code> associated with this mapper.
		 * @param map The <code>Map</code> associated with this mapper.
		 */	
		public StaticMapper(Address address, InetSocketAddress socketAddress, Map<Address, InetSocketAddress> map) {
			super(address, socketAddress);
			
			this.map = map;
		}
	
		@Override
		public InetSocketAddress lookup(Address address) throws IOException, InterruptedException {
			return map.get(address);
		}
	}
}

