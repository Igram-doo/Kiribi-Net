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

import java.security.SecureRandom;
import java.security.GeneralSecurityException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;

import rs.igram.kiribi.io.VarInput;
import rs.igram.kiribi.io.VarOutputStream;
import rs.igram.kiribi.io.ByteUtils;

import static rs.igram.kiribi.net.Magic.random;

/**
 *
 * @author Michael Sargent
 */
final class Magic {
	private static final byte[] MAGIC 	= Base64.getDecoder().decode("8sVHh3rgzP8=");
	private static final int MAGIC_LEN = MAGIC.length; // 8
	static final SecureRandom random;

 	static {
		try{
			random = SecureRandom.getInstance("SHA1PRNG", "SUN"); 
		}catch(Exception e){
			throw new RuntimeException("Could not initialize secure random",e);
		}
	}
	
	static void random(byte[] bytes) {
		random.nextBytes(bytes);
	}
	
	private Magic() {}
	
	public static byte[] magic() throws IOException {
		var m = new byte[MAGIC_LEN];
		random(m);
		ByteUtils.or(m, MAGIC, MAGIC_LEN);
		return m;
	}
	
	public static void magic(byte[] dst, int offset) throws IOException {
		var m = new byte[MAGIC_LEN];
		random(m);
		ByteUtils.or(m, MAGIC, MAGIC_LEN);
		System.arraycopy(m, 0, dst, offset, MAGIC_LEN);
	}
	
	public static boolean magic(byte[] m) throws IOException {
		ByteUtils.and(m, MAGIC, MAGIC_LEN);
		return Arrays.equals(m, MAGIC);
	}
	
	public static boolean verifyMagic(byte[] src, int offset) throws IOException {
		var m = new byte[MAGIC_LEN];
		System.arraycopy(src, offset, m, 0, MAGIC_LEN);
		return magic(m);
	}

	public static void magic(VarOutputStream out) throws IOException {
		out.write(magic());
		out.flush();
	}
	
	public static boolean magic(VarInput in) throws IOException {
		var m = new byte[MAGIC_LEN];
		in.readFully(m);
		return magic(m);
	}
}