
/**
 * StopWaitFtp Class
 * 
 * StopWaitFtp implements the sending side of 
 * the stop and wait protocol and adds
 * functionality to recover from packet losses.
 * 
 * CPSC 441
 * Assignment 3
 * 
 */

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;

public class StopWaitFtp {
	private static final Logger logger = Logger.getLogger("StopWaitFtp");

	private final int connTimeout;
	private final int rtxTimeout;

	private Timer timer;
	private TimerTask timeoutHandler;
	private DatagramSocket udpSocket;

	private int serverUdpPort;
	private int seqNum;

	// Flag to indicate ACK has been received or not
	private boolean ackReceived;
	
	// Initializing data output, input streams, and the file input streams as null for now
	private Socket tcpSocket = null;
	private DataOutputStream dataOutputStream = null;
	private DataInputStream dataInputStream = null;
	private FileInputStream fileInputStream = null;

	// Server's address
	private InetAddress serverAddress;

	/**
	 * Constructor to initialize the program
	 * 
	 * @param connTimeout The time-out interval for non-responsive server
	 * @param rtxTimeout  The time-out interval for the retransmission timer
	 */
	public StopWaitFtp(int connTimeout, int rtxTimeout) {
		this.connTimeout = connTimeout;
		this.rtxTimeout = rtxTimeout;
	}

	/**
	 * Send the specified file to the specified remote server
	 * 
	 * @param serverName Name of the remote server
	 * @param serverPort Port number of the remote server
	 * @param fileName   Name of the file to be transferred to the remote server
	 * @return true if the file transfer completed successfully, false otherwise
	 */
	public boolean send(String serverName, int serverPort, String fileName) {

		try {
			// Open a TCP connection to the server
			tcpSocket = new Socket(serverName, serverPort);
			dataOutputStream = new DataOutputStream(tcpSocket.getOutputStream());
			dataInputStream = new DataInputStream(tcpSocket.getInputStream());

			// Open a UDP socket
			udpSocket = new DatagramSocket();
			udpSocket.setSoTimeout(connTimeout);
			serverAddress = InetAddress.getByName(serverName);

			// Creating file object
			File file = new File(fileName);

			// Complete the handshake over TCP
			// Send the name of the file as a UTF encoded string
			dataOutputStream.writeUTF(file.getName());

			// Send the length (in bytes) of the file as a long value
			dataOutputStream.writeLong(file.length());

			// Send the local UDP port number used for file transfer as an int value
			dataOutputStream.writeInt(udpSocket.getLocalPort());

			// flush the the output stream to force TCP to send data to server
			dataOutputStream.flush();

			// Receive the server UDP port number used for file transfer as an int value
			serverUdpPort = dataInputStream.readInt();

			// Receive the initial sequence number used by the server as an int value
			seqNum = dataInputStream.readInt();
			System.out.println("Handshake completed between Client & Server");

			// Initialize fileInputStream
			fileInputStream = new FileInputStream(file);

			// Make the send byte have maximum payload size
			byte[] send = new byte[FtpSegment.MAX_PAYLOAD_SIZE];
			int bytesRead;

			// Send the file to the server in segments
			// Make sure to wait for ACK right after sending segment
			// Increment sequence number
			while ((bytesRead = fileInputStream.read(send)) != -1) {
				FtpSegment segment = new FtpSegment(seqNum, send, bytesRead);
				sendSegment(segment);

				// Wait for ACK
				waitForAck(segment);
				seqNum++;
			}

			// Return true if no issue
			return true;

		} catch (Exception e) {
			// Return false if there is issue
			return false;

		} finally {
			// This cleans up the UDP side
			udpCleanUp();

			// This cleans up the TCP side
			tcpCleanUp();

		}
	}

	/**
	 * Sends the segment over UDP and starts a retransmission timer
	 *
	 * @param segment is the FtpSegment
	 * @throws IOException
	 */
	private void sendSegment(FtpSegment segment) throws IOException {
		DatagramPacket packet = FtpSegment.makePacket(segment, serverAddress, serverUdpPort);
		udpSocket.send(packet);
		System.out.println("send " + segment.getSeqNum());
		startTimer(segment);

	}

	/**
	 * Retransmit the segment over UDP
	 * 
	 * @param segment is the FtpSegment
	 * @throws IOException
	 */
	private void retxSegment(FtpSegment segment) throws IOException {
		DatagramPacket packet = FtpSegment.makePacket(segment, serverAddress, serverUdpPort);
		udpSocket.send(packet);
		System.out.println("retx " + segment.getSeqNum());
	}

	/**
	 * Waits for ACK
	 *
	 * @param segment is the FtpSegment
	 * @throws IOException
	 */
	private void waitForAck(FtpSegment segment) {
		// Initialize ackReceived as false
		ackReceived = false;

		// Start tracking connection time
		long startTime = System.currentTimeMillis();

		// Start retransmission timer
		startTimer(segment);

		while (!ackReceived) {
			try {
				// Make the receive byte have maximum segment size
				byte[] receive = new byte[FtpSegment.MAX_SEGMENT_SIZE];
				DatagramPacket ackPacket = new DatagramPacket(receive, receive.length);

				// Receive the incoming packet from the UDP socket
				udpSocket.receive(ackPacket);

				FtpSegment ackSegment = new FtpSegment(ackPacket);
				int ackNum = ackSegment.getSeqNum();

				// If the expected ACK number has been received, then set boolean value as true,
				// and stop the timer for retransmissions
				if (ackNum == segment.getSeqNum() + 1) {
					ackReceived = true;
					System.out.println("ack " + ackNum);
					stopTimer();
				} else {
					// Prints the unexpected ACK and the expected ACK
					System.out.println("unexpected ACK: " + ackNum + ", expected: " + (segment.getSeqNum() + 1));
				}
			} catch (SocketTimeoutException e) {
				long totalTime = System.currentTimeMillis() - startTime;

				// If the total time calculated is more than the connection timeout, then we do
				// UDP clean up and we break out of the loop
				if (totalTime > connTimeout) {
					System.out.println("connection timeout: server is non-responsive");
					udpCleanUp();
					tcpCleanUp();
					break;
				}
			} catch (IOException e) {
			}
		}
	}

	/**
	 * Starts a retransmission timer for the given segment
	 *
	 * @param segment is the FtpSegment
	 */
	private synchronized void startTimer(FtpSegment segment) {
		// stop timer first to make sure no timer is currently running
		stopTimer();

		// start new timer
		timer = new Timer();

		// declare the current TimerTask
		timeoutHandler = new TimeoutHandler(segment);

		// Schedule the retransmission task to run periodically
		timer.schedule(timeoutHandler, rtxTimeout, rtxTimeout);
	}

	/**
	 * Stops the current retransmission timer
	 */
	private synchronized void stopTimer() {
		// If timeoutHandler is not null, then cancel the timeoutHandler
		if (timeoutHandler != null) {
			timeoutHandler.cancel();
		}
		
		// If timer is not null, then cancel the timer
		if (timer != null) {
			timer.cancel();
		}
	}

	/**
	 * Cleans up UDP socket and stops timer
	 */
	private void udpCleanUp() {
		// Stop the timer
		stopTimer();
		
		// Close the UDP socket
		if (udpSocket != null && !udpSocket.isClosed()) {
			udpSocket.close();
		}
		
		// Set the boolean value for expected ACK being received as true
		// This help prevented the infinite printing of "timeout" 
		ackReceived = true;
	}
	
	/**
	 * Cleans up TCP socket, and closes the data output, input stream, and file input stream
	 */
	private void tcpCleanUp() {
		try {
			dataOutputStream.close();
			dataInputStream.close();
			tcpSocket.close();
			fileInputStream.close();
		} catch (IOException e) {
		}
	}

	/**
	 * This is the TimeoutHandler class needed for the retransmission
	 */
	class TimeoutHandler extends TimerTask {
		private FtpSegment segment;

		// Constructor
		public TimeoutHandler(FtpSegment segment) {
			this.segment = segment;
		}

		// Overridden run method
		@Override
		public void run() {
			// if ACK was not received, this means that a timeout occurred, so then we
			// retransmit the segment
			try {
				if (!ackReceived) {
					System.out.println("timeout");
					retxSegment(segment);
				}
			} catch (IOException e) {
				// Catches the exception thrown in retxSegment
			}
		}
	}
}
