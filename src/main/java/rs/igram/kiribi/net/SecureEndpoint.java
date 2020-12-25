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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import rs.igram.kiribi.crypto.KeyExchange;
import rs.igram.kiribi.io.ByteStream;
import rs.igram.kiribi.io.Decoder;
import rs.igram.kiribi.io.Encodable;
import rs.igram.kiribi.io.VarInput;
import rs.igram.kiribi.io.VarInputStream;
import rs.igram.kiribi.io.VarOutput;
import rs.igram.kiribi.io.VarOutputStream;

import static rs.igram.kiribi.io.ByteUtils.extract;

/**
 * 
 *
 * @author Michael Sargent
 */
abstract class SecureEndpoint implements Endpoint {
	// control flags
	static final byte INIT  = 1; 
	static final byte DATA  = 2; 
	static final byte RESET = 3; 
	static final byte CLOSE = 4; 
	byte flag = INIT;
	// handshake timeout in  seconds
	protected static final long HANDSHAKE_TIMEOUT = 5;
	protected KeyExchange exchanger;
	protected int protocolVersion;
	private CountDownLatch latch = new CountDownLatch(1);
	boolean isProxy;
	Address remote;
		
	protected synchronized Endpoint connect(boolean isProxy) throws IOException {
		this.isProxy = isProxy;
			
		// magic and version
		byte[] buf = new byte[10];
		NetVersion version = NetVersion.current();

		if(isProxy){
			buf[0] = INIT;
			buf[1] = (byte)version.protocolVersion;
			Magic.magic(buf, 2);
			writeRaw(buf);
				
			buf = readRaw();
			if(buf[0] != INIT){
				throw new IOException("Attempt to initialize connection with wrong control flag: "+buf[0]);
			}
			boolean b = Magic.verifyMagic(buf, 2);
			if(!b){
				throw new IOException("Bad Voodoo");
			}
			protocolVersion = buf[1];
		}else{
			buf = readRaw();
			if(buf[0] != INIT){
				throw new IOException("Attempt to initialize connection with wrong control flag: "+buf[0]);
			}
			protocolVersion = Math.min(buf[1], version.protocolVersion);
			boolean b = Magic.verifyMagic(buf, 2);
			if(!b){
				throw new IOException("Bad Voodoo");
			}
			buf[1] = (byte)protocolVersion;
			writeRaw(buf);
		}

		// key exchange
		ByteStream stream = new ByteStream() {
			@Override
			public void write(byte[] b) throws IOException {
				byte[] d = new byte[b.length + 1];
				d[0] = flag;
				System.arraycopy(b, 0, d, 1, b.length);
				writeRaw(d);
			}
			@Override
			public byte[] read() throws IOException {
				byte[] b = readRaw();
				return extract(b, 1, b.length - 1);
			}
		};
		exchanger = new KeyExchange(isProxy, stream);
		try{
			exchanger.exchange();
		}catch(ArrayIndexOutOfBoundsException e){
			// ec will throw this if something is wonky
			throw new IOException(e);
		}
			
		flag = DATA;
		latch.countDown();
			
		return this;
	}
				
	protected abstract void writeRaw(byte[] b) throws IOException;
	protected abstract byte[] readRaw() throws IOException;
		
	@Override
	public void write(Encodable data) throws IOException {
		try{
			latch.await(HANDSHAKE_TIMEOUT, TimeUnit.SECONDS);
			exchanger.write(data);
		}catch(Exception e){
			throw new IOException(e);
		}
	}

	@Override
	public <T> T read(Decoder<T> decoder) throws IOException {
		try{
			latch.await(HANDSHAKE_TIMEOUT, TimeUnit.SECONDS);
			return exchanger.read(decoder);
		}catch(Exception e){
			throw new IOException(e);
		}
	}
}
