# Kiribi-Net
Kiribi Network Module


### Introduction
Provides classes and interfaces to support NAT transversal and peer-to-peer network communication.

### Features
* TCP EndpointProvider.
* UDP EndpointProvider.
* The UDP Communication Protocol supports Keep Alive and Reliable Message Transmission.
* Each UDP peer only requires a single open udp port on the host device. The NAT Transversal Protocol, UDP Communication Protocol and Endpoint Data Transfer Protocol packets are automatically multi-plexed.
* Encryption between peers is provided by the [Kiribi-Crypto](http://github.com/Igram-doo/Kiribi-Crypto) module.
* NAT Transversal Server to facilitate UPD Endpoint connection.

### Overview
Provides classes and interfaces to support NAT transversal and peer-to-peer network communication.

##### Addresses
Each UDP peer maintains a unique Address. Addresses can be instatiated with a crypto-graphic public key to ensure they are unique.

##### Endpoints
* Endpoint: Provides similar functionality to the standard Java Socket.
* ServerEndpoint: Provides similar functionality to the standard Java ServerSocket.

##### Endpoint Providers
Factories for creating endpoints. Currently the following two EndpointProviders are implemented:

* TCPEndpointProvider: creates TCP Endpoints
* UDPEndpointProvider: creates UDP Endpoints

##### Network Executor
Wrapper for a Java ForkJoinPool and ScheduledExecutorService to facilitate multi-threading.

##### Network Monitor
Provides static methods to monitor network availability.

### Code Examples
##### UDP
	// The NetworkExecutor to use
	NetworkExecutor executor = ...
	// The Address of this peer
	Address address = ... 
	// The SocketAddress of the NAT Server
	SocketAddress serverAddress = ...
	
	EndpointProvider<ConnectionAddress> udpProvider = 
		EndpointProvider.udpProvider(executor, address, serverAddress);
		
	// A user defined unique long
	long id = ...
	// The Address of the peer we want to connect to
	Address peer = ...
	
	ConnectionAddress connectionAddress = 
		new ConnectionAddress(peer, id);
		
	Endpoint endpoint = udpProvider.open(connectionAddress);

	// The port we want to listen on
	int port = ...
	
	ServerEndpoint serverEndpoint = udpProvider.open(port);
		
##### TCP
	// The NetworkExecutor to use
	NetworkExecutor executor = ...
	
	EndpointProvider<SocketAddress> tcpProvider = 
		EndpointProvider.tcpProvider(executor);
		
	// The SocketAddress of the TCP server we want to connect to
	SocketAddress socketAddress = ...
		
	Endpoint endpoint = tcpProvider.open(socketAddress);

	// The port we want to listen on
	int port = ...
	
	ServerEndpoint serverEndpoint = tcpProvider.open(port);
	
### Module Dependencies
##### Requires
* java.base
* java.logging
* rs.igram.kiribi.io
* rs.igram.kiribi.crypto

##### Exports
* rs.igram.kiribi.net

### To Do
* Determine minimum supported Java version.
* Finish unit tests.
* Add Logging.
* Add blurb about NAT transversal and router types.
* Add link to check if router supports NAT transversal.
* Add blurb about router configuration.
* Add multicast discovery for use on a LAN.
* SCTP EndpointProvider implementation.
