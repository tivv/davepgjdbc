/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/jdbc3/Jdbc3TestSuite.java,v 1.12 2005/02/01 07:27:55 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc3;

import junit.framework.TestSuite;

/*
 * Executes all known tests for JDBC3
 */
public class Jdbc3TestSuite extends TestSuite
{

    /*
     * The main entry point for JUnit
     */
    public static TestSuite suite()
    {
        TestSuite suite = new TestSuite();
        suite.addTestSuite(Jdbc3SavepointTest.class);
        suite.addTestSuite(TypesTest.class);
        suite.addTestSuite(ResultSetTest.class);
        suite.addTestSuite(ParameterMetaDataTest.class);
        suite.addTestSuite(Jdbc3BlobTest.class);
        return suite;
    }
}
