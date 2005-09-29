/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/jdbc2/BlobTest.java,v 1.14.2.2 2005/05/08 23:17:48 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import junit.framework.TestCase;
import java.io.*;
import java.sql.*;

import org.postgresql.largeobject.*;

/**
 * Some simple tests based on problems reported by users. Hopefully these will
 * help prevent previous problems from re-occuring ;-)
 *
 */
public class BlobTest extends TestCase
{

    private Connection con;

    private static final int LOOP = 0; // LargeObject API using loop
    private static final int NATIVE_STREAM = 1; // LargeObject API using OutputStream

    public BlobTest(String name)
    {
        super(name);
    }

    protected void setUp() throws Exception
    {
        con = TestUtil.openDB();
        TestUtil.createTable(con, "testblob", "id name,lo oid");
        con.setAutoCommit(false);
    }

    protected void tearDown() throws Exception
    {
        con.setAutoCommit(true);
        TestUtil.dropTable(con, "testblob");
        TestUtil.closeDB(con);
    }

    public void testSetNull() throws Exception
    {
        PreparedStatement pstmt = con.prepareStatement("INSERT INTO testblob(lo) VALUES (?)");

        pstmt.setBlob(1, null);
        pstmt.executeUpdate();

        pstmt.setNull(1, Types.BLOB);
        pstmt.executeUpdate();

        pstmt.setObject(1, null, Types.BLOB);
        pstmt.executeUpdate();

        pstmt.setClob(1, null);
        pstmt.executeUpdate();

        pstmt.setNull(1, Types.CLOB);
        pstmt.executeUpdate();

        pstmt.setObject(1, null, Types.CLOB);
        pstmt.executeUpdate();
    }
    
    public void testSet() throws SQLException {
        Statement stmt = con.createStatement();
        stmt.execute("INSERT INTO testblob(id,lo) VALUES ('1', lo_creat(-1))");
        ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob");
        assertTrue(rs.next());

        PreparedStatement pstmt = con.prepareStatement("INSERT INTO testblob(id, lo) VALUES(?,?)");

        Blob blob = rs.getBlob(1);
        pstmt.setString(1,"2");
        pstmt.setObject(2, blob, Types.BLOB);
        assertEquals(1, pstmt.executeUpdate());

        blob = rs.getBlob(1);
        pstmt.setString(1,"3");
        pstmt.setBlob(2, blob);
        assertEquals(1, pstmt.executeUpdate());

        Clob clob = rs.getClob(1);
        pstmt.setString(1,"4");
        pstmt.setObject(2, clob, Types.CLOB);
        assertEquals(1, pstmt.executeUpdate());

        clob = rs.getClob(1);
        pstmt.setString(1,"5");
        pstmt.setClob(2, clob);
        assertEquals(1, pstmt.executeUpdate());
    }

    /*
     * Tests one method of uploading a blob to the database
     */
    public void testUploadBlob_LOOP() throws Exception
    {
        assertTrue(uploadFile("build.xml", LOOP) > 0);

        // Now compare the blob & the file. Note this actually tests the
        // InputStream implementation!
        assertTrue(compareBlobsLOAPI());
        assertTrue(compareBlobs());
        assertTrue(compareClobs());
    }

    /*
     * Tests one method of uploading a blob to the database
     */
    public void testUploadBlob_NATIVE() throws Exception
    {
        assertTrue(uploadFile("build.xml", NATIVE_STREAM) > 0);

        // Now compare the blob & the file. Note this actually tests the
        // InputStream implementation!
        assertTrue(compareBlobs());
    }

    public void testGetBytesOffset() throws Exception
    {
        assertTrue(uploadFile("build.xml", NATIVE_STREAM) > 0);

        Statement stmt = con.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob");
        assertTrue(rs.next());

        Blob lob = rs.getBlob(1);
        byte data[] = lob.getBytes(2,4);
        assertEquals(data.length, 4);
        assertEquals(data[0], '?');
        assertEquals(data[1], 'x');
        assertEquals(data[2], 'm');
        assertEquals(data[3], 'l');
    }

    /*
     * Helper - uploads a file into a blob using old style methods. We use this
     * because it always works, and we can use it as a base to test the new
     * methods.
     */
    private int uploadFile(String file, int method) throws Exception
    {
        LargeObjectManager lom = ((org.postgresql.PGConnection)con).getLargeObjectAPI();

        FileInputStream fis = new FileInputStream(file);

        int oid = lom.create(LargeObjectManager.READWRITE);
        LargeObject blob = lom.open(oid);

        int s, t;
        byte buf[];
        OutputStream os;

        switch (method)
        {
        case LOOP:
            buf = new byte[2048];
            t = 0;
            while ((s = fis.read(buf, 0, buf.length)) > 0)
            {
                t += s;
                blob.write(buf, 0, s);
            }
            break;

        case NATIVE_STREAM:
            os = blob.getOutputStream();
            s = fis.read();
            while (s > -1)
            {
                os.write(s);
                s = fis.read();
            }
            os.close();
            break;

        default:
            assertTrue("Unknown method in uploadFile", false);
        }

        blob.close();
        fis.close();

        // Insert into the table
        Statement st = con.createStatement();
        st.executeUpdate(TestUtil.insertSQL("testblob", "id,lo", "'" + file + "'," + oid));
        con.commit();
        st.close();

        return oid;
    }

    /*
     * Helper - compares the blobs in a table with a local file. Note this uses
     * the postgresql specific Large Object API
     */
    private boolean compareBlobsLOAPI() throws Exception
    {
        boolean result = true;

        LargeObjectManager lom = ((org.postgresql.PGConnection)con).getLargeObjectAPI();

        Statement st = con.createStatement();
        ResultSet rs = st.executeQuery(TestUtil.selectSQL("testblob", "id,lo"));
        assertNotNull(rs);

        while (rs.next())
        {
            String file = rs.getString(1);
            int oid = rs.getInt(2);

            FileInputStream fis = new FileInputStream(file);
            LargeObject blob = lom.open(oid);
            InputStream bis = blob.getInputStream();

            int f = fis.read();
            int b = bis.read();
            int c = 0;
            while (f >= 0 && b >= 0 & result)
            {
                result = (f == b);
                f = fis.read();
                b = bis.read();
                c++;
            }
            result = result && f == -1 && b == -1;

            if (!result)
                assertTrue("Large Object API Blob compare failed at " + c + " of " + blob.size(), false);

            blob.close();
            fis.close();
        }
        rs.close();
        st.close();

        return result;
    }

    /*
     * Helper - compares the blobs in a table with a local file. This uses the 
     * jdbc java.sql.Blob api
     */
    private boolean compareBlobs() throws Exception
    {
        boolean result = true;

        Statement st = con.createStatement();
        ResultSet rs = st.executeQuery(TestUtil.selectSQL("testblob", "id,lo"));
        assertNotNull(rs);

        while (rs.next())
        {
            String file = rs.getString(1);
            Blob blob = rs.getBlob(2);

            FileInputStream fis = new FileInputStream(file);
            InputStream bis = blob.getBinaryStream();

            int f = fis.read();
            int b = bis.read();
            int c = 0;
            while (f >= 0 && b >= 0 & result)
            {
                result = (f == b);
                f = fis.read();
                b = bis.read();
                c++;
            }
            result = result && f == -1 && b == -1;

            if (!result)
                assertTrue("JDBC API Blob compare failed at " + c + " of " + blob.length(), false);

            bis.close();
            fis.close();
        }
        rs.close();
        st.close();

        return result;
    }

    /*
     * Helper - compares the clobs in a table with a local file. 
     */
    private boolean compareClobs() throws Exception
    {
        boolean result = true;

        Statement st = con.createStatement();
        ResultSet rs = st.executeQuery(TestUtil.selectSQL("testblob", "id,lo"));
        assertNotNull(rs);

        while (rs.next())
        {
            String file = rs.getString(1);
            Clob clob = rs.getClob(2);

            FileInputStream fis = new FileInputStream(file);
            InputStream bis = clob.getAsciiStream();

            int f = fis.read();
            int b = bis.read();
            int c = 0;
            while (f >= 0 && b >= 0 & result)
            {
                result = (f == b);
                f = fis.read();
                b = bis.read();
                c++;
            }
            result = result && f == -1 && b == -1;

            if (!result)
                assertTrue("Clob compare failed at " + c + " of " + clob.length(), false);

            bis.close();
            fis.close();
        }
        rs.close();
        st.close();

        return result;
    }
}
