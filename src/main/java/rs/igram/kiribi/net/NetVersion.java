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

import rs.igram.kiribi.io.Encodable;
import rs.igram.kiribi.io.VarInput; 
import rs.igram.kiribi.io.VarOutput;

/** 
 * Kiribi network protocol version.
 *
 * @author Michael Sargent
 */
final class NetVersion implements Encodable {
	public static final NetVersion V1 = new NetVersion(1,1,1);
	
	public final int version;
	public final int serialVersion;
	public final int protocolVersion;
	
	private NetVersion(int version, int serialVersion, int protocolVersion) {
		this.version = version;
		this.serialVersion = serialVersion;
		this.protocolVersion = protocolVersion;
	}
	
	public NetVersion(VarInput in) throws IOException {
		version = in.readInt();
		serialVersion = in.readInt();
		protocolVersion = in.readInt();
	}

	public static NetVersion current() {return V1;}
	
	@Override
	public void write(VarOutput out) throws IOException {
		out.writeInt(version);
		out.writeInt(serialVersion);
		out.writeInt(protocolVersion);
	}
	@Override
	public String toString(){
		return "NetVersion[version="+version+",serial="+serialVersion+",protocol="+protocolVersion+"]";
	}
	@Override
	public boolean equals(Object obj){
		if(obj != null && obj instanceof NetVersion){
			NetVersion v = (NetVersion)obj;
			return version == v.version
				&& serialVersion == v.serialVersion
				&& protocolVersion == v.protocolVersion;
		}
		return false;
	}
}
