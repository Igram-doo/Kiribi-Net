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
 
package rs.igram.kiribi.net.stack.natt;

/**
 * 
 *
 * @author Michael Sargent
 */
public final class NATTProtocol {	
	public static enum SessionState {INIT, FAILED, AVAILABLE}
	public static enum SessionType {NATT, SOCKET}
	
	public static final byte REG = 1; // register - kiribi address
	public static final byte CON = 2; // connection request - kiribi address
	public static final byte TUN = 3; // tunnel request - remote socket address
	public static final byte ADR = 4; // register response - remote socket address
	public static final byte ADC = 5; // connection response - remote socket address
	public static final byte ERR = 7;
	
	public static final byte SYN = 1;
	public static final byte ACK = 2;
	public static final byte FIN = 3;

	// offsets
//	public static final int OFF_ID		= 1;
	public static final int OFF_CMD 	= 9;
	public static final int OFF_DATA	= 10;
	
	private NATTProtocol() {}
}
