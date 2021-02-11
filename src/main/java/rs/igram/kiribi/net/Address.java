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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Base64;

import rs.igram.kiribi.io.ByteUtils;
import rs.igram.kiribi.io.Encodable;
import rs.igram.kiribi.io.VarInput;
import rs.igram.kiribi.io.VarOutput;

/**
 * An instance of this class encapsulates a cryptographic hash of a public key.
 *
 * @author Michael Sargent
 */
public final class Address implements Encodable {
	/**
	 * An "empty" address.
	 */
	public static final Address NULL = new Address(new byte[20]);
	
	/**
	 * The hash of the associated key's data.
	 */
	final byte[] bytes;
	
	/**
	 * Initializes a newly created <code>Address</code> object from
	 * the provided byte array.
	 *
	 * <p>The byte array must be of length 20. Note that the following will hold:
	 * <pre>
	 * Address address1 = ...
	 * byte[] b = address toByteArray();
	 * Address address2 = new Address(b);
	 * address1.equals(address2) == true;
	 * </pre>
	 *
	 * @param hash The byte array to initialize from.
	 */
	public Address(byte[] hash){
		if(hash.length != 20) throw new IllegalArgumentException("Input array wrong size: "+hash.length);
		bytes = hash;
	}
	
	/**
	 * Initializes a newly created <code>Address</code> object
	 *  from the provided <code>PublicKey</code>.
	 *
	 * @param key The <code>PublicKey</code> to initialize from.
	 */
	public Address(PublicKey key) {
		bytes = ByteUtils.crop(sha256(key.getEncoded()), 20);
	}
	
    private static final byte[] sha256(byte[] data) {
    	try{
    		return MessageDigest.getInstance("SHA-256").digest(data);
    	}catch(NoSuchAlgorithmException e){
    		// sha256 is required to be supported
    		throw new RuntimeException(e);
    	}
    }

	/**
	 * Initializes a newly created <code>Address</code> object
	 * so that it reads from the provided <code>VarInput</code>.
	 *
	 * @param in The input stream to read from.
	 * @throws IOException if there was a problem reading from the provided 
	 * <code>VarInputStream</code>.
	 */
	public Address(VarInput in) throws IOException {
		bytes = new byte[20];
		in.readFully(bytes);
	}
	
	/**
	 * Initializes a newly created <code>Address</code> object
	 * from the provided string.
	 *
	 * <p>Note that the following will hold:
	 * <pre>
	 * Address address1 = ...
	 * String s = address toString();
	 * Address address2 = new Address(s);
	 * address1.equals(address2) == true;
	 * </pre>
	 *
	 * @param s The base 64 encoding of the hash of the associated key's data.
	 * @see #toString
	 */
	public Address(String s) {
		this(Base64.getUrlDecoder().decode(s));
	}

	/**
	 * Returns the byte array associated with this address.
	 *
	 * @return The byte array associated with this address.
	 */
	public byte[] bytes() {
		return ByteUtils.copy(bytes);
	}
	
	@Override
	public void write(VarOutput out) throws IOException {
		out.write(bytes);
	}
		
	
	/**
	 * Returns the base 64 encoding of the hash of the associated key's data.
	 *
	 * @return The base 64 encoding of the hash of the associated key's data.
	 * @see #Address(String)
	 */
	@Override
	public String toString() {return Base64.getUrlEncoder().encodeToString(bytes);}

	@Override
	public int hashCode() {return Arrays.hashCode(bytes);}

	@Override
	public boolean equals(Object o){
		if(this == o) return true;
		if(o != null && o.getClass() == Address.class){
			var a = (Address)o;
			return Arrays.equals(bytes, a.bytes);
		}
		return false;
	}
}

