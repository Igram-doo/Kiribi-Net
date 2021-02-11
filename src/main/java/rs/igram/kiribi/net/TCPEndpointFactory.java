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
import java.net.SocketAddress;
import java.nio.channels.AcceptPendingException;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.Channels;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import rs.igram.kiribi.io.VarInputStream;
import rs.igram.kiribi.io.VarOutputStream;
/**
 * Endpoint factory for TCP endpoints.
 *
 * @author Michael Sargent
 */
public abstract class TCPEndpointFactory {
	
	private TCPEndpointFactory() {}

	/**
	 * Returns an endpoint.
	 *
	 * @param address The <code>SocketAddress</code> to connect to.
	 * @return Returns an endpoint.
	 * @throws IOException if there was a problem opening the endpoint.
	 * @throws InterruptedException if the provider was interrupted while opening the endpoint.
	 * @throws ExecutionException if there was a problem opening the endpoint.
	 */
	public static Endpoint open(SocketAddress address)
		throws IOException, InterruptedException, ExecutionException {

		final var channel = AsynchronousSocketChannel.open();
		channel.connect(address).get();
		return new ChannelEndpoint(channel).connect(true);
	}

	/**
	 * Returns a server endpoint.
	 *
	 * @param socketAddress The <code>SocketAddress</code> to listen on.
	 * @return Returns a server endpoint.
	 * @throws IOException if there was a problem opening the endpoint.
	 */
	public static ServerEndpoint server(SocketAddress socketAddress) 
		throws IOException {	
			
		final var channel = AsynchronousServerSocketChannel.open();
		channel.bind(socketAddress);
		return  new ServerChannelEndpoint(channel);
	}

	static class ChannelEndpoint extends SecureEndpoint {
		private AsynchronousSocketChannel channel;
		private VarInputStream in;
		private VarOutputStream out;

		ChannelEndpoint(AsynchronousSocketChannel channel) {
			this.channel = channel;
			in = new VarInputStream(Channels.newInputStream(channel));
			out = new VarOutputStream(Channels.newOutputStream(channel));
		}

		@Override
		protected void writeRaw(byte[] b) throws IOException {
			out.writeBytes(b);
			out.flush();
		}
		
		@Override
		protected byte[] readRaw() throws IOException {
			return in.readBytes();
		}

		@Override
		public boolean isOpen() {return channel.isOpen();}

		@Override
		public void close() throws IOException {
			if(channel != null && channel.isOpen()) channel.close();
		}
		
		public SocketAddress remote() throws IOException {
			return channel.getRemoteAddress();
		}
	}

	static class ServerChannelEndpoint implements ServerEndpoint {
		AsynchronousServerSocketChannel channel;

		ServerChannelEndpoint(AsynchronousServerSocketChannel channel) {
			this.channel = channel;
		}

		@Override
		public void accept(Consumer<Endpoint> consumer) throws IOException {
			try{
				if(channel.isOpen()){
					channel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Object>() {
						@Override
						public void completed(final AsynchronousSocketChannel c, Object attachment) {
							if(channel.isOpen()){
								channel.accept(null, this);
							}
							try{
								var endpoint = new ChannelEndpoint(c).connect(false);
								consumer.accept(endpoint);
							}catch(IOException e){
								// couldn't connect - chuck
							}
						}
						@Override
						public void failed(Throwable t, Object attachment) {
							if(channel.isOpen()) channel.accept(null, this);
						}
					});
				}
			}catch(AcceptPendingException e) {
				// ignore
			}
		}

		@Override
		public boolean isOpen() {return channel.isOpen();}

		@Override
		public void close() throws IOException {channel.close();}
	}
}
