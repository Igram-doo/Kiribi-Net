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

//import rs.igram.kiribi.crypto.Address;

/**
 * An instance of this class represents a UDP connection address.
 *
 * @author Michael Sargent
 */
public final class ConnectionAddress {
	/** The address of this <code>ConnectionAddress</code> instance. */
	public final Address address;
	/** The id of this <code>ConnectionAddress</code> instance. */
	public final long id;
		
	/**
	 * Instantiates a new <code>ConnectionAddress</code> instance.
	 *
	 * @param address The address of this <code>ConnectionAddress</code> instance.
	 * @param id The user defined id of this <code>ConnectionAddress</code> instance.
	 * Note: each <code>ConnectionAddress</code> should have a unique id.
	 */
	public ConnectionAddress(Address address, long id) {
		this.address = address;
		this.id = id;
	}
}
