/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/PGStream.java,v 1.23 2010/08/31 18:07:39 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.Socket;
import java.sql.SQLException;
import java.util.Properties;

/**
  * Wrapper around the raw connection to the server that implements some basic
  * primitives (reading/writing formatted data, doing string encoding, etc).
  *<p>
  * In general, instances of PGStream are not threadsafe; the caller must ensure
  * that only one thread at a time is accessing a particular PGStream instance.
 */
public interface PGStream {
    String getHost();

    int getPort();

    Socket getSocket();

    /**
     * Check for pending backend messages without blocking.
     * Might return false when there actually are messages
     * waiting, depending on the characteristics of the
     * underlying socket. This is used to detect asynchronous
     * notifies from the backend, when available.
     *
     * @return true if there is a pending backend message
     */
    boolean hasMessagePending() throws IOException;

    /**
     * Switch this stream to using a new socket. Any existing socket
     * is <em>not</em> closed; it's assumed that we are changing to
     * a new socket that delegates to the original socket (e.g. SSL).
     *
     * @param socket the new socket to change to
     * @throws IOException if something goes wrong
     */
    void changeSocket(Socket socket) throws IOException;

    Encoding getEncoding();

    /**
     * Change the encoding used by this connection.
     *
     * @param encoding the new encoding to use
     * @throws IOException if something goes wrong
     */
    void setEncoding(Encoding encoding) throws IOException;

    /**
     * Get a Writer instance that encodes directly onto the underlying stream.
     *<p>
     * The returned Writer should not be closed, as it's a shared object.
     * Writer.flush needs to be called when switching between use of the Writer and
     * use of the PGStream write methods, but it won't actually flush output
     * all the way out -- call {@link #flush} to actually ensure all output
     * has been pushed to the server.
     *
     * @return the shared Writer instance
     * @throws IOException if something goes wrong.
     */
    Writer getEncodingWriter() throws IOException;

    /**
     * Sends a single character to the back end
     *
     * @param val the character to be sent
     * @exception IOException if an I/O error occurs
     */
    void SendChar(int val) throws IOException;

    /**
     * Sends a 4-byte integer to the back end
     *
     * @param val the integer to be sent
     * @exception IOException if an I/O error occurs
     */
    public void SendInteger4(int val) throws IOException;

    /**
     * Sends a 2-byte integer (short) to the back end
     *
     * @param val the integer to be sent
     * @exception IOException if an I/O error occurs or <code>val</code> cannot be encoded in 2 bytes
     */
    public void SendInteger2(int val) throws IOException;

    /**
     * Send an array of bytes to the backend
     *
     * @param buf The array of bytes to be sent
     * @exception IOException if an I/O error occurs
     */
    public void Send(byte buf[]) throws IOException;

    /**
     * Send a fixed-size array of bytes to the backend. If buf.length < siz,
     * pad with zeros. If buf.lengh > siz, truncate the array.
     *
     * @param buf the array of bytes to be sent
     * @param siz the number of bytes to be sent
     * @exception IOException if an I/O error occurs
     */
    public void Send(byte buf[], int siz) throws IOException;

    /**
     * Send a fixed-size array of bytes to the backend. If length < siz,
     * pad with zeros. If length > siz, truncate the array.
     *
     * @param buf the array of bytes to be sent
     * @param off offset in the array to start sending from
     * @param siz the number of bytes to be sent
     * @exception IOException if an I/O error occurs
     */
    public void Send(byte buf[], int off, int siz) throws IOException;

    /**
     * Receives a single character from the backend, without
     * advancing the current protocol stream position.
     *
     * @return the character received
     * @exception IOException if an I/O Error occurs
     */
    public int PeekChar() throws IOException;

    /**
     * Receives a single character from the backend
     *
     * @return the character received
     * @exception IOException if an I/O Error occurs
     */
    public int ReceiveChar() throws IOException;

    /**
     * Receives a four byte integer from the backend
     *
     * @return the integer received from the backend
     * @exception IOException if an I/O error occurs
     */
    public int ReceiveInteger4() throws IOException;

    /**
     * Receives a two byte integer from the backend
     *
     * @return the integer received from the backend
     * @exception IOException if an I/O error occurs
     */
    int ReceiveInteger2() throws IOException;

    /**
     * Receives a fixed-size string from the backend.
     *
     * @param len the length of the string to receive, in bytes.
     * @return the decoded string
     */
    String ReceiveString(int len) throws IOException;

    /**
     * Receives a null-terminated string from the backend. If we don't see a
     * null, then we assume something has gone wrong.
     *
     * @return string from back end
     * @exception IOException if an I/O error occurs, or end of file
     */
    public String ReceiveString() throws IOException;


    /**
     * Read a tuple from the back end. A tuple is a two dimensional
     * array of bytes. This variant reads the V3 protocol's tuple
     * representation.
     *
     * @return null if the current response has no more tuples, otherwise
     * an array of bytearrays
     * @exception IOException if a data I/O error occurs
     */
    byte[][] ReceiveTupleV3() throws IOException, OutOfMemoryError;

    /**
     * Read a tuple from the back end. A tuple is a two dimensional
     * array of bytes. This variant reads the V2 protocol's tuple
     * representation.
     *
     * @param nf the number of fields expected
     * @param bin true if the tuple is a binary tuple
     * @return null if the current response has no more tuples, otherwise
     * an array of bytearrays
     * @exception IOException if a data I/O error occurs
     */
    byte[][] ReceiveTupleV2(int nf, boolean bin) throws IOException, OutOfMemoryError;

    /**
     * Reads in a given number of bytes from the backend
     *
     * @param siz number of bytes to read
     * @return array of bytes received
     * @exception IOException if a data I/O error occurs
     */
    byte[] Receive(int siz) throws IOException;

    /**
     * Reads in a given number of bytes from the backend
     *
     * @param buf buffer to store result
     * @param off offset in buffer
     * @param siz number of bytes to read
     * @exception IOException if a data I/O error occurs
     */
    void Receive(byte[] buf, int off, int siz) throws IOException;

    void Skip(int size) throws IOException;

    /**
     * Copy data from an input stream to the connection.
     *
     * @param inStream the stream to read data from
     * @param remaining the number of bytes to copy
     */
    void SendStream(InputStream inStream, int remaining) throws IOException;

    /**
     * Flush any pending output to the backend.
     * @exception IOException if an I/O error occurs
     */
    void flush() throws IOException;

    /**
     * Consume an expected EOF from the backend
     * @exception SQLException if we get something other than an EOF
     */
    void ReceiveEOF() throws SQLException, IOException;

    /**
     * Closes the connection
     *
     * @exception IOException if an I/O Error occurs
     */
    void close() throws IOException;

    /**
     * Establish new connection to the server
     * @param closeOriginal if to close this socket (true) or leave it connected (false)
     * @return newly established connection
     * @throws IOException if an I/O Error occurs
     */
    PGStream reconnect(boolean closeOriginal) throws IOException;

    /**
     * Enables SSL on the stream
     * @param requireSSL is SSL is required or optional
     * @param info connection properties
     * @param logger where to log o
     * @return either this connection or new one if reconnect was required
     * @throws IOException if an I/O Error occurs
     * @throws SQLException if a protocol-level error occurs
     */
    PGStream enableSSL(boolean requireSSL, Properties info, Logger logger) throws IOException, SQLException;

    /**
     * Sets new read listener
     * @param newListener read listener to set
     * @return old read listener or null if listeners are not supported
     */
    StreamReadListener readListener(StreamReadListener newListener) throws IOException;

    /**
     * Adds task to be executed in this connection
     * @param timerTask
     * @param milliSeconds
     */
    public void addTimerTask(Runnable timerTask, long milliSeconds);
}
