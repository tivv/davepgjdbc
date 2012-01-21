/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2012, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/PGStream.java,v 1.24 2011/08/02 13:40:12 davecramer Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.TreeSet;

/**
 * PGStream implementation using java NIO v1
 */
public class PGNIOStream extends AbstractPGStream {

    private SocketChannel connection;
    private SelectionKey readWriteSelectionKey, readSelectionKey, writeSelectionKey;
    private ByteBuffer pg_input;
    private ByteBuffer pg_output;
    private StreamReadListener readListener;
    private final TreeSet taskQueue = new TreeSet();

    private Encoding encoding;

    /**
     * Constructor:  Connect to the PostgreSQL back end and return
     * a stream connection.
     *
     * @param host the hostname to connect to
     * @param port the port number that the postmaster is sitting on
     * @exception java.io.IOException if an IOException occurs below it.
     */
    public PGNIOStream(String host, int port) throws IOException
    {
        super(host, port);

        // Buffer sizes submitted by Sverre H Huseby <sverrehu@online.no>
        pg_input = ByteBuffer.allocate(8192).order(ByteOrder.BIG_ENDIAN);
        pg_output = ByteBuffer.allocate(8192).order(ByteOrder.BIG_ENDIAN);

        setEncoding(Encoding.getJVMEncoding("US-ASCII"));

        changeSocket(SocketChannel.open(new InetSocketAddress(host, port)).socket());
    }

    public Socket getSocket() {
        return connection.socket();
    }

    /**
     * Sets new read listener
     * @param newListener read listener to set
     * @return old read listener
     */
    public StreamReadListener readListener(StreamReadListener newListener) throws IOException {
        StreamReadListener rc = readListener;
        readListener = newListener;
        if (rc == null ^ newListener == null) {
            if (readWriteSelectionKey != null)
                readWriteSelectionKey.selector().close();

            readWriteSelectionKey = connection.register(Selector.open(),
                    readListener == null ? SelectionKey.OP_WRITE : SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        }
        return rc;
    }

    public void addTimerTask(Runnable timerTask, long milliSeconds) {
        long startTime = System.currentTimeMillis() + milliSeconds;
        synchronized (taskQueue) {
            taskQueue.add(new DelayedTask(timerTask, startTime));
        }
    }

    /**
     * Check for pending backend messages without blocking.
     * Might return false when there actually are messages
     * waiting, depending on the characteristics of the
     * underlying socket. This is used to detect asynchronous
     * notifies from the backend, when available.
     *
     * @return true if there is a pending backend message
     */
    public boolean hasMessagePending() throws IOException {
        tryRead(readSelectionKey);
        return pg_input.hasRemaining();
    }

    /**
     * Switch this stream to using a new socket. Any existing socket
     * is <em>not</em> closed; it's assumed that we are changing to
     * a new socket that delegates to the original socket (e.g. SSL).
     *
     * @param socket the new socket to change to
     * @throws java.io.IOException if something goes wrong
     */
    public void changeSocket(Socket socket) throws IOException {
        this.connection = socket.getChannel();

        connection.configureBlocking(false);

        // Submitted by Jason Venner <jason@idiom.com>. Disable Nagle
        // as we are selective about flushing output only when we
        // really need to.
        socket.setTcpNoDelay(true);

        pg_input.clear().limit(0);
        pg_output.clear();

        if (readSelectionKey != null)
            readSelectionKey.selector().close();
        readSelectionKey = connection.register(Selector.open(), SelectionKey.OP_READ);

        if (readWriteSelectionKey != null)
            readWriteSelectionKey.selector().close();
        readWriteSelectionKey = connection.register(Selector.open(), SelectionKey.OP_READ | SelectionKey.OP_WRITE);

        if (writeSelectionKey != null)
            writeSelectionKey.selector().close();
        writeSelectionKey = connection.register(Selector.open(), SelectionKey.OP_WRITE);
    }

    public Encoding getEncoding() {
        return encoding;
    }

    /**
     * Change the encoding used by this connection.
     *
     * @param encoding the new encoding to use
     * @throws java.io.IOException if something goes wrong
     */
    public void setEncoding(Encoding encoding) throws IOException {
        this.encoding = encoding;
    }

    /**
     * This writer is used by V2 connections only
     * and we don't support this type of stream for V2
     *
     * @throws UnsupportedOperationException
     */
    public Writer getEncodingWriter() throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * Sends a single character to the back end
     *
     * @param val the character to be sent
     * @exception java.io.IOException if an I/O error occurs
     */
    public void SendChar(int val) throws IOException
    {
        ensureWriteSpace(1);
        pg_output.put((byte) val);
    }

    /**
     * Sends a 4-byte integer to the back end
     *
     * @param val the integer to be sent
     * @exception java.io.IOException if an I/O error occurs
     */
    public void SendInteger4(int val) throws IOException
    {
        ensureWriteSpace(4);
        pg_output.putInt(val);
    }

    /**
     * Sends a 2-byte integer (short) to the back end
     *
     * @param val the integer to be sent
     * @exception java.io.IOException if an I/O error occurs or <code>val</code> cannot be encoded in 2 bytes
     */
    public void SendInteger2(int val) throws IOException
    {
        if (val < Short.MIN_VALUE || val > Short.MAX_VALUE)
            throw new IOException("Tried to send an out-of-range integer as a 2-byte value: " + val);

        ensureWriteSpace(2);
        pg_output.putShort((short) val);
    }

    /**
     * Send an array of bytes to the backend
     *
     * @param buf The array of bytes to be sent
     * @exception java.io.IOException if an I/O error occurs
     */
    public void Send(byte buf[]) throws IOException
    {
        Send(buf, buf.length);
    }

    /**
     * Send a fixed-size array of bytes to the backend. If length < siz,
     * pad with zeros. If length > siz, truncate the array.
     *
     * @param buf the array of bytes to be sent
     * @param off offset in the array to start sending from
     * @param siz the number of bytes to be sent
     * @exception java.io.IOException if an I/O error occurs
     */
    public void Send(byte buf[], int off, int siz) throws IOException
    {
        if (siz <= pg_output.remaining()) {
            pg_output.put(buf, off, siz);
            return;
        }
    	int bufamt = buf.length - off;
        int sendSize = bufamt < siz ? bufamt : siz;
        flush();
        Send(ByteBuffer.wrap(buf, off, sendSize));
        for (int i = bufamt ; i < siz ; ++i)
        {
            SendChar(0);
        }
    }

    /**
     * Sends all data available in ByteBuffer. Clears buffer afterwards
     */
    private void Send(ByteBuffer buffer) throws IOException {
        SelectionKey selectionKey = readListener != null ? readWriteSelectionKey : writeSelectionKey;
        while (buffer.hasRemaining()) {
            if (connection.write(buffer) == 0) {
                if (readListener != null) {
                    tryRead(selectionKey);
                    if (pg_input.hasRemaining()) {
                        while (pg_input.hasRemaining())
                            readListener.canRead();
                    } else {
                        select(selectionKey);
                    }
                }
            }
        }
        buffer.clear();
    }

    /**
     * Receives a single character from the backend, without
     * advancing the current protocol stream position.
     *
     * @return the character received
     * @exception java.io.IOException if an I/O Error occurs
     */
    public int PeekChar() throws IOException
    {
        ensureReadData(1);
        return pg_input.get(pg_input.position());
    }

    /**
     * Receives a single character from the backend
     *
     * @return the character received
     * @exception java.io.IOException if an I/O Error occurs
     */
    public int ReceiveChar() throws IOException
    {
        ensureReadData(1);
        return pg_input.get();
    }

    /**
     * Receives a four byte integer from the backend
     *
     * @return the integer received from the backend
     * @exception java.io.IOException if an I/O error occurs
     */
    public int ReceiveInteger4() throws IOException
    {
        ensureReadData(4);
        return pg_input.getInt();
    }

    /**
     * Receives a two byte integer from the backend
     *
     * @return the integer received from the backend
     * @exception java.io.IOException if an I/O error occurs
     */
    public int ReceiveInteger2() throws IOException
    {
        ensureReadData(2);
        return pg_input.getShort();
    }

    /**
     * Receives a fixed-size string from the backend.
     *
     * @param len the length of the string to receive, in bytes.
     * @return the decoded string
     */
    public String ReceiveString(int len) throws IOException {
        ensureReadData(len);
        String res = encoding.decode(pg_input.array(), pg_input.arrayOffset() + pg_input.position(),
                                     len);
        pg_input.position(pg_input.position() + len);
        return res;
    }

    /**
     * Receives a null-terminated string from the backend. If we don't see a
     * null, then we assume something has gone wrong.
     *
     * @return string from back end
     * @exception java.io.IOException if an I/O error occurs, or end of file
     */
    public String ReceiveString() throws IOException
    {
        String rc = ReceiveString(scanCStringLength());
        pg_input.position(pg_input.position() + 1);
        return rc;
    }

    /**
     * Ensures pg_input has full C-style string (0-ended)
     * @return full C-String length, not including 0 byte
     * @throws java.io.IOException if an I/O error occurs, or end of file
     */
    private int scanCStringLength() throws IOException {
        for (int i = 0;; i++) {
            if (pg_input.remaining() < i) {
                ensureReadData(i + VisibleBufferedInputStream.STRING_SCAN_SPAN);
            }
            if (pg_input.get(pg_input.position() + i) == 0)
                return i;
        }
    }

    private void tryRead(SelectionKey selectionKey) throws IOException {
        if (pg_input.hasRemaining())
            return;
        pg_input.clear();
        try {
            tryRead(pg_input, selectionKey);
        }finally {
            pg_input.flip();
        }
    }

    private void ensureWriteSpace(int bytes) throws IOException {
        if (pg_output.remaining() < bytes)
            flush();
    }

    /**
     * Ensures input has required number of bytes
     * @param len how many bytes should be in buffer on exit
     * @throws IOException if an I/O error occurs, or end of file
     */
    private void ensureReadData(int len) throws IOException{
        if (pg_input.remaining() >= len)
            return;
        if (!pg_input.hasRemaining())
            pg_input.clear().limit(0);
        int startPosition = 0;
        if (pg_input.capacity() < len) {
            pg_input = ByteBuffer.allocate(Math.max(len, pg_input.capacity() * 2)).put(pg_input);
        } else if (pg_input.capacity() - pg_input.position() < len) {
            pg_input.compact();
        } else {
            startPosition = pg_input.position();
            pg_input.position(startPosition + pg_input.remaining()).limit(pg_input.capacity());

        }
        try {
            while (pg_input.position() - startPosition < len) {
                blockingRead(pg_input);
            }
        }finally {
            pg_input.flip().position(startPosition);
        }
    }

    private void blockingRead(ByteBuffer buffer) throws IOException {
        if (tryRead(buffer, readSelectionKey) > 0)
            return;
        select(readSelectionKey);
        if (tryRead(buffer, readSelectionKey) == 0)
            throw new IOException("Can't read anything after select");
    }

    private int tryRead(ByteBuffer buffer, SelectionKey selectionKey) throws IOException {
        int read = connection.read(buffer);
        if (read == 0) {
            readSelectionKey.selector().selectedKeys().remove(selectionKey);
        } else  if (read < 0)
            throw new EOFException();
        return read;
    }

    private int select(SelectionKey selectionKey) throws IOException {
        int selected;
        do {
            long timeout = runQueue();
            selected = selectionKey.selector().select(timeout);
        } while (selected == 0);
        return selectionKey.readyOps();
    }

    private long runQueue() {
        synchronized (taskQueue) {
            if (taskQueue.isEmpty())
                return 0;
            for (Iterator i = taskQueue.iterator(); i.hasNext();) {
                DelayedTask task = (DelayedTask) i.next();
                long timeout = task.runTime - System.currentTimeMillis();
                if (timeout <= 0) {
                    i.remove();
                    task.task.run();
                } else {
                    return timeout;
                }
            }
        }
        return 0;
    }

    /**
     * Reads in a given number of bytes from the backend
     *
     * @param buf buffer to store result
     * @param off offset in buffer
     * @param siz number of bytes to read
     * @exception java.io.IOException if a data I/O error occurs
     */
    public void Receive(byte[] buf, int off, int siz) throws IOException
    {
        if (siz <= pg_input.remaining()) {
            pg_input.get(buf, off, siz);
            return;
        }
        if (siz < VisibleBufferedInputStream.MINIMUM_READ) {
            ensureReadData(siz);
            pg_input.get(buf, off, siz);
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(buf, off, siz);
        buffer.put(pg_input);
        while (buffer.hasRemaining())
            blockingRead(buffer);
    }

    public void Skip(int size) throws IOException {
        while (size > 0) {
            if (pg_input.remaining() > size) {
                pg_input.position(pg_input.position() + size);
                return;
            }
            size -= pg_input.remaining();
            pg_input.clear().limit(0);
            ensureReadData(Math.min(size, pg_input.capacity()));
        }
    }


    /**
     * Flush any pending output to the backend.
     * @exception java.io.IOException if an I/O error occurs
     */
    public void flush() throws IOException
    {
        pg_output.flip();
        Send(pg_output);
    }

    /**
     * Consume an expected EOF from the backend
     * @exception java.sql.SQLException if we get something other than an EOF
     */
    public void ReceiveEOF() throws SQLException, IOException
    {
        try {
            throw new PSQLException(GT.tr("Expected an EOF from server, got: {0}", new Integer(PeekChar())), PSQLState.COMMUNICATION_ERROR);
        } catch (EOFException e) {
            return;
        }
    }

    /**
     * Closes the connection
     *
     * @exception java.io.IOException if an I/O Error occurs
     */
    public void close() throws IOException
    {
        if (readSelectionKey != null) {
            readSelectionKey.selector().close();
            readSelectionKey = null;
        }
        if (readWriteSelectionKey != null) {
            readWriteSelectionKey.selector().close();
            readWriteSelectionKey = null;
        }
        if (writeSelectionKey != null) {
            writeSelectionKey.selector().close();
            writeSelectionKey = null;
        }
        connection.close();
    }

    public PGStream reconnect(boolean closeOriginal) throws IOException {
        if (closeOriginal)
            close();

        return new PGNIOStream(getHost(), getPort());
    }

    public static class DelayedTask implements Comparable{
        //We are using this to prevent collisions in TreeSet
        private static long NUMBER;
        private final Runnable task;
        private final long runTime;
        private final long number;

        public DelayedTask(Runnable task, long runTime) {
            this.task = task;
            this.runTime = runTime;
            this.number = getNextNumber();
        }

        private synchronized static long getNextNumber() {
            return  NUMBER++;
        }

        public int compareTo(Object obj) {
            DelayedTask o = (DelayedTask) obj;
            return runTime  < o.runTime ? -1 : runTime != o.runTime ? 1 :
                    number < o.number ? -1 : 1;
        }
    }
}
