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
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Logger;

import rs.igram.kiribi.io.VarInputStream;
import rs.igram.kiribi.io.VarOutputStream;

/**
 * 
 *
 * @author Michael Sargent
 */
final class TCPEndpointProvider extends EndpointProvider<SocketAddress> {
	private static final Logger LOGGER = Logger.getLogger(TCPEndpointProvider.class.getName());
	
	public TCPEndpointProvider(NetworkExecutor executor) {
		super(executor);
	}

	@Override
	public Endpoint open(SocketAddress address)
		throws IOException, InterruptedException {

		final AsynchronousSocketChannel channel = AsynchronousSocketChannel.open();
		try{
			channel.connect(address).get();
			return new ChannelEndpoint(channel).connect(true);
		}catch(Exception e){
			throw new IOException(e);
		}
	}

	@Override
	public ServerEndpoint open(int port)
		throws IOException, InterruptedException, TimeoutException{

		final AsynchronousServerSocketChannel channel = AsynchronousServerSocketChannel.open();
		channel.bind(new InetSocketAddress(port));
		return new ServerChannelEndpoint(channel);
	}

	private static class ChannelEndpoint extends SecureEndpoint {
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
	}

	private static class ServerChannelEndpoint implements ServerEndpoint {
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
								Endpoint endpoint = new ChannelEndpoint(c).connect(false);
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
