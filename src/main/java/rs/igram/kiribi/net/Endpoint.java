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
import java.net.SocketAddress;
import java.util.function.Consumer;

import rs.igram.kiribi.io.EncodedStream;

/**
 * Connection endpoints implement this interface.
 *
 * @author Michael Sargent
 */
public interface Endpoint extends EncodedStream {
	/**
	 * Returns <code>true</code> if this enpoint is open, <code>false</code> otherwise.
	 *
	 * @return <code>true</code> if this enpoint is open, <code>false</code> otherwise.
	 */
	boolean isOpen();
	
	/**
	 * Closes this endpoint.
	 *
	 * @throws IOException if there was a problem closing this endpoint.
	 */
	void close() throws IOException;
	
	/**
	 * Returns the remote <code>SocketAddress</code> this enpoint is connected to.
	 *
	 * @return The remote <code>SocketAddress</code> this enpoint is connected to.
	 * @throws IOException if there was a problem determining the remote socket address.
	 */
	default SocketAddress remote() throws IOException {
		throw new UnsupportedOperationException();
	}
	
	/**
	 * This method allows consumers to register for connection state changes.
	 *
	 * @param consumer The consumer which will consume connection state changes.
	 */
	default void state(Consumer<ConnectionState> consumer) {}
}
