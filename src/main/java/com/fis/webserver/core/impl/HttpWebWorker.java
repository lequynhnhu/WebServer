package com.fis.webserver.core.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.apache.log4j.Logger;

import com.fis.webserver.core.WebWorker;
import com.fis.webserver.model.SocketReadPayload;
import com.fis.webserver.model.http.HttpResponse;

/**
 * WebWorker implementation
 * 
 * @author Florin Iordache
 *
 */

public class HttpWebWorker implements WebWorker {
	public static final Logger logger = Logger.getLogger(HttpWebWorker.class);
	
	//maximum number of clients this worker can handle
	private int maxClients;
	
	//number of available client slots
	private int freeClientSlots;
	
	//internal selector this worker is monitoring
	private Selector socketSelector;
	
	// queue containing the new SocketChannels that need to be listened by this
	// worker
	private BlockingQueue<SocketChannel> newClientsQueue;
	
	// work queue for pushing the read data
	private BlockingQueue<SocketReadPayload> workQueue;
	
	//map with the pending responses that need to be sent back to the clients
	private HashMap<SelectionKey, HttpResponse> pendingResponses;
	
	public HttpWebWorker(int maxClients, BlockingQueue<SocketReadPayload> workQueue) {
		this.maxClients = maxClients;
		this.freeClientSlots = maxClients;
		
		this.workQueue = workQueue;
		
		newClientsQueue = new ArrayBlockingQueue<SocketChannel>(10);
		
		pendingResponses = new HashMap<SelectionKey, HttpResponse>();
		
		try {
			// Create a new selector
		    socketSelector = SelectorProvider.provider().openSelector();
		}
		catch(Exception e) {
			logger.error("Could not initialize socket selector!", e);
		}
	}
	
	@Override
	public void run() {

		//handle the reading requests and writing responses to the registered channels
		while(true) {
			try {
				logger.debug("Waiting to read data");
				
				//wait for at least one incoming connection
				socketSelector.select();
				
				//check for pending changes
				SocketChannel newChannel = newClientsQueue.poll();
				if(newChannel != null) {
					//configure the channel for non-blocking mode
					newChannel.configureBlocking(false);
					//register this socket channel for the read operation
					newChannel.register(socketSelector, SelectionKey.OP_READ);
				}
				
				//iterate over the available selection keys
				Iterator<SelectionKey> selectedKeysIterator = socketSelector.selectedKeys().iterator(); 
				while(selectedKeysIterator.hasNext()) {
					SelectionKey selectionKey = selectedKeysIterator.next();
					
					//remove from set to prevent future processing
					selectedKeysIterator.remove();
					
					//check if key is valid
					if(!selectionKey.isValid()) {
						continue;
					}
					
					//read the data
					if(selectionKey.isReadable()) {
						logger.debug("Reading request from socket");
						
						readRequest(selectionKey);
						
					}
					else if( selectionKey.isWritable() ) {
						logger.debug("Writing response to socket");
						
						writeResponse(selectionKey);
					}
				}
				
			}
			catch(Exception e) {
				logger.error("Error while waiting for new connection!", e);
			}
		}
	}

	@Override
	public boolean handle(SocketChannel socketChannel) {
		
		// accept the client for handling only if there are still more client
		// slots available
		if( freeClientSlots > 1 ) {
			newClientsQueue.add(socketChannel);
			socketSelector.wakeup();
			freeClientSlots --;
			return true;
		}
		
		return false;
	}
	
	/**
	 * Handles the data reading operation from the socket channel associated
	 * with this key
	 * 
	 * @param key
	 *            The key representing the socket channel that has data
	 *            available for reading
	 */
	private void readRequest(SelectionKey key) {
		//get the associated socket channel
		SocketChannel socketChannel = (SocketChannel) key.channel();
		
		//prepare the byte buffer
		ByteBuffer readBuffer = ByteBuffer.allocate(16384);
		
		//attempt to read from the socket
		int bytesRead = -1;
		try {
			bytesRead = socketChannel.read(readBuffer);
		}
		catch(IOException e) {
			//the remote host has closed the connection
			closeChannel(key);
		}

		/*
		 * check if we were able to read something; if not, the remote
		 * connection has been closed cleanly
		 */
		if( bytesRead < 0 ) {
			closeChannel(key);
		}
		
		Charset charset = Charset.forName("UTF-8");
		CharsetDecoder decoder = charset.newDecoder();
		decoder.reset();
		
		try {
			readBuffer.position(0);
			CharBuffer decodedBuffer = decoder.decode(readBuffer);
			logger.debug("Read from socket: " + decodedBuffer.toString());
			
			//push the new data in the work queue
			workQueue.put(new SocketReadPayload(key, decodedBuffer));
		}
		catch (CharacterCodingException e) {
			logger.error("Error decoding message read from socket!", e);
		}
		catch (InterruptedException ie) {
			logger.error("Worker interrupted while pushing newly read data to the work queue!", ie);
		}
	}

	/**
	 * Sends the response to the socket channel associated with this key
	 * 
	 * @param key
	 *            Key representing the socket channel where the response will be
	 *            written
	 */
	private void writeResponse(SelectionKey key) {
		//write a dummy response and close the channel;
		SocketChannel socketChannel = (SocketChannel) key.channel();
		
		//get the queued response
		HttpResponse response = pendingResponses.get(key);
		if( response == null ) {
			logger.error("No response available for sending!");
		}
		
		ByteBuffer writeBuffer = ByteBuffer.wrap(response.getRawResponse().toString().getBytes());
		
		
		try {
			socketChannel.write(writeBuffer);
		} catch (IOException e) {
			logger.error("Error writing to socket!", e);
		}
		
		closeChannel(key);
	}

	@Override
	public void closeChannel(SelectionKey key) {
		try {
			key.channel().close();
		}
		catch(IOException e) {
			logger.warn("Exception while shutting down channel!", e);
		}
		key.cancel();
		freeClientSlots ++;
	}

	@Override
	public boolean isHandlingClient(SelectionKey key) {
		return socketSelector.keys().contains(key);
	}

	@Override
	public void sendResponse(SelectionKey key, HttpResponse response) {
		//register the socket channel for read operation
		key.interestOps(SelectionKey.OP_WRITE);
		
		//queue the response
		pendingResponses.put(key, response);
		
		socketSelector.wakeup();
	}

	@Override
	public int getFreeSlots() {
		return freeClientSlots;
	}
}