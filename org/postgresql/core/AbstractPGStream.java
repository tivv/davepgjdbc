/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/BaseConnection.java,v 1.27 2011/09/26 12:52:30 davecramer Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Properties;

/**
 * This class implements high-level PGStream methods common to NIO and Stream
 * implementations
 */
public abstract class AbstractPGStream implements PGStream {
    protected final String host;
    protected final int port;
    private byte[] streamBuffer;

    public AbstractPGStream(String host, int port) {
        this.port = port;
        this.host = host;
    }

    /**
     * Read a tuple from the back end. A tuple is a two dimensional
     * array of bytes. This variant reads the V3 protocol's tuple
     * representation.
     *
     * @return null if the current response has no more tuples, otherwise
     * an array of bytearrays
     * @exception java.io.IOException if a data I/O error occurs
     */
    public byte[][] ReceiveTupleV3() throws IOException, OutOfMemoryError
    {
        //TODO: use l_msgSize
        int l_msgSize = ReceiveInteger4();
        int i;
        int l_nf = ReceiveInteger2();
        byte[][] answer = new byte[l_nf][];

        OutOfMemoryError oom = null;
        for (i = 0 ; i < l_nf ; ++i)
        {
            int l_size = ReceiveInteger4();
            if (l_size != -1) {
                try {
                    answer[i] = new byte[l_size];
                    Receive(answer[i], 0, l_size);
                } catch(OutOfMemoryError oome) {
                    oom = oome;
                    Skip(l_size);
                }
            }
        }

        if (oom != null)
            throw oom;

        return answer;
    }

    /**
     * Read a tuple from the back end. A tuple is a two dimensional
     * array of bytes. This variant reads the V2 protocol's tuple
     * representation.
     *
     * @param nf the number of fields expected
     * @param bin true if the tuple is a binary tuple
     * @return null if the current response has no more tuples, otherwise
     * an array of bytearrays
     * @exception java.io.IOException if a data I/O error occurs
     */
    public byte[][] ReceiveTupleV2(int nf, boolean bin) throws IOException, OutOfMemoryError
    {
        int i, bim = (nf + 7) / 8;
        byte[] bitmask = Receive(bim);
        byte[][] answer = new byte[nf][];

        int whichbit = 0x80;
        int whichbyte = 0;

        OutOfMemoryError oom = null;
        for (i = 0 ; i < nf ; ++i)
        {
            boolean isNull = ((bitmask[whichbyte] & whichbit) == 0);
            whichbit >>= 1;
            if (whichbit == 0)
            {
                ++whichbyte;
                whichbit = 0x80;
            }
            if (!isNull)
            {
                int len = ReceiveInteger4();
                if (!bin)
                    len -= 4;
                if (len < 0)
                    len = 0;
                try {
                    answer[i] = new byte[len];
                    Receive(answer[i], 0, len);
                } catch(OutOfMemoryError oome) {
                    oom = oome;
                    Skip(len);
                }
            }
        }

        if (oom != null)
            throw oom;

        return answer;
    }

    public PGStream enableSSL(boolean requireSSL, Properties info, Logger logger) throws IOException, SQLException {
        if (logger.logDebug())
            logger.debug(" FE=> SSLRequest");

        // Send SSL request packet
        SendInteger4(8);
        SendInteger2(1234);
        SendInteger2(5679);
        flush();

        // Now get the response from the backend, one of N, E, S.
        int beresp = ReceiveChar();
        switch (beresp)
        {
        case 'E':
            if (logger.logDebug())
                logger.debug(" <=BE SSLError");

            // Server doesn't even know about the SSL handshake protocol
            if (requireSSL)
                throw new PSQLException(GT.tr("The server does not support SSL."), PSQLState.CONNECTION_REJECTED);

            // We have to reconnect to continue.
            return reconnect(true);

        case 'N':
            if (logger.logDebug())
                logger.debug(" <=BE SSLRefused");

            // Server does not support ssl
            if (requireSSL)
                throw new PSQLException(GT.tr("The server does not support SSL."), PSQLState.CONNECTION_REJECTED);

            return this;

        case 'S':
            if (logger.logDebug())
                logger.debug(" <=BE SSLOk");

            // Server supports ssl
            org.postgresql.ssl.MakeSSL.convert(this, info, logger);
            return this;

        default:
            throw new PSQLException(GT.tr("An error occured while setting up the SSL connection."), PSQLState.PROTOCOL_VIOLATION);
        }
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /**
     * Send a fixed-size array of bytes to the backend. If buf.length < siz,
     * pad with zeros. If buf.lengh > siz, truncate the array.
     *
     * @param buf the array of bytes to be sent
     * @param siz the number of bytes to be sent
     * @exception java.io.IOException if an I/O error occurs
     */
    public void Send(byte buf[], int siz) throws IOException
    {
        Send(buf, 0, siz);
    }

    /**
     * Reads in a given number of bytes from the backend
     *
     * @param siz number of bytes to read
     * @return array of bytes received
     * @exception java.io.IOException if a data I/O error occurs
     */
    public byte[] Receive(int siz) throws IOException
    {
        byte[] answer = new byte[siz];
        Receive(answer, 0, siz);
        return answer;
    }

    /**
     * Copy data from an input stream to the connection.
     *
     * @param inStream the stream to read data from
     * @param remaining the number of bytes to copy
     */
    public void SendStream(InputStream inStream, int remaining) throws IOException {
        int expectedLength = remaining;
        if (streamBuffer == null)
            streamBuffer = new byte[8192];

        while (remaining > 0)
        {
            int count = (remaining > streamBuffer.length ? streamBuffer.length : remaining);
            int readCount;

            try
            {
                readCount = inStream.read(streamBuffer, 0, count);
                if (readCount < 0)
                    throw new EOFException(GT.tr("Premature end of input stream, expected {0} bytes, but only read {1}.", new Object[]{new Integer(expectedLength), new Integer(expectedLength - remaining)}));
            }
            catch (IOException ioe)
            {
                while (remaining > 0)
                {
                    Send(streamBuffer, count);
                    remaining -= count;
                    count = (remaining > streamBuffer.length ? streamBuffer.length : remaining);
                }
                throw new PGBindException(ioe);
            }

            Send(streamBuffer, readCount);
            remaining -= readCount;
        }
    }
}
