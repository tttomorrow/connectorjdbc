/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.InputStream;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;

/**
 * Some simple tests based on problems reported by users. Hopefully these will help prevent previous
 * problems from re-occurring ;-)
 */
public class BlobTest {
  private static final int LOOP = 0; // LargeObject API using loop
  private static final int NATIVE_STREAM = 1; // LargeObject API using OutputStream

  private Connection con;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    // blob and clob can exchange must set string is not varchar!
    props.setProperty(PGProperty.STRING_TYPE.getName(), "unspecified");
    con = TestUtil.openDB(props);
    TestUtil.createTable(con, "testblob", "id name,lo blob");
    con.setAutoCommit(false);
  }

  @After
  public void tearDown() throws Exception {
    con.setAutoCommit(true);
    TestUtil.dropTable(con, "testblob");
    TestUtil.closeDB(con);
  }

  @Test
  public void testSetNull() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testblob(lo) VALUES (?)");

    pstmt.setBlob(1, (Blob) null);
    pstmt.executeUpdate();

    pstmt.setNull(1, Types.BLOB);
    pstmt.executeUpdate();

    pstmt.setObject(1, null, Types.BLOB);
    pstmt.executeUpdate();
  }

  @Test
  public void testSet() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("INSERT INTO testblob(id,lo) VALUES ('1', empty_blob())");
    ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob");
    assertTrue(rs.next());

    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testblob(id, lo) VALUES(?,?)");

    Blob blob = rs.getBlob(1);
    pstmt.setString(1, "setObjectTypeBlob");
    pstmt.setObject(2, blob, Types.BLOB);
    assertEquals(1, pstmt.executeUpdate());

    blob = rs.getBlob(1);
    pstmt.setString(1, "setObjectBlob");
    pstmt.setObject(2, blob);
    assertEquals(1, pstmt.executeUpdate());

    blob = rs.getBlob(1);
    pstmt.setString(1, "setBlob");
    pstmt.setBlob(2, blob);
    assertEquals(1, pstmt.executeUpdate());

    Clob clob = rs.getClob(1);
    pstmt.setString(1, "setObjectTypeClob");
    pstmt.setObject(2, clob, Types.CLOB);
    assertEquals(1, pstmt.executeUpdate());

    clob = rs.getClob(1);
    pstmt.setString(1, "setObjectClob");
    pstmt.setObject(2, clob);
    assertEquals(1, pstmt.executeUpdate());

    clob = rs.getClob(1);
    pstmt.setString(1, "setClob");
    pstmt.setClob(2, clob);
    assertEquals(1, pstmt.executeUpdate());
  }

  /*
   * Tests one method of uploading a blob to the database
   */
  @Test
  public void testUploadBlob_LOOP() throws Exception {
    assertTrue(uploadFile("/test-file.xml", LOOP) > 0);

    // Now compare the blob & the file. Note this actually tests the
    // InputStream implementation!
    assertTrue(compareBlobs());
  }

  /*
   * Tests one method of uploading a blob to the database
   */
  @Test
  public void testUploadBlob_NATIVE() throws Exception {
    assertTrue(uploadFile("/test-file.xml", NATIVE_STREAM) > 0);

    // Now compare the blob & the file. Note this actually tests the
    // InputStream implementation!
    assertTrue(compareBlobs());
  }

  @Test
  public void testMarkResetStream() throws Exception {
    assertTrue(uploadFile("/test-file.xml", NATIVE_STREAM) > 0);

    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob");
    assertTrue(rs.next());

    InputStream bis = rs.getBlob(1).getBinaryStream();

    assertEquals('<', bis.read());
    bis.mark(4);
    assertEquals('?', bis.read());
    assertEquals('x', bis.read());
    assertEquals('m', bis.read());
    assertEquals('l', bis.read());
    bis.reset();
    assertEquals('?', bis.read());
  }


  @Test
  public void testGetBytesOffset() throws Exception {
    assertTrue(uploadFile("/test-file.xml", NATIVE_STREAM) > 0);

    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob");
    assertTrue(rs.next());

    Blob lob = rs.getBlob(1);
    byte[] data = lob.getBytes(2, 4);
    assertEquals(data.length, 4);
    assertEquals(data[0], '?');
    assertEquals(data[1], 'x');
    assertEquals(data[2], 'm');
    assertEquals(data[3], 'l');
  }

  @Test
  public void testMultipleStreams() throws Exception {
    assertTrue(uploadFile("/test-file.xml", NATIVE_STREAM) > 0);

    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob");
    assertTrue(rs.next());

    Blob lob = rs.getBlob(1);
    byte[] data = new byte[2];

    InputStream is = lob.getBinaryStream();
    assertEquals(data.length, is.read(data));
    assertEquals(data[0], '<');
    assertEquals(data[1], '?');
    is.close();

    is = lob.getBinaryStream();
    assertEquals(data.length, is.read(data));
    assertEquals(data[0], '<');
    assertEquals(data[1], '?');
    is.close();
  }

  @Test
  public void testParallelStreams() throws Exception {
    assertTrue(uploadFile("/test-file.xml", NATIVE_STREAM) > 0);

    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob");
    assertTrue(rs.next());

    Blob lob = rs.getBlob(1);
    InputStream is1 = lob.getBinaryStream();
    InputStream is2 = lob.getBinaryStream();

    while (true) {
      int i1 = is1.read();
      int i2 = is2.read();
      assertEquals(i1, i2);
      if (i1 == -1) {
        break;
      }
    }

    is1.close();
    is2.close();
  }

  @Test
  public void testLargeLargeObject() throws Exception {
    if (!TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_3)) {
      return;
    }

    Statement stmt = con.createStatement();
    stmt.execute("INSERT INTO testblob(id,lo) VALUES ('1', lo_creat(-1))");
    ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob");
    assertTrue(rs.next());

    Blob lob = rs.getBlob(1);
    long length = ((long) Integer.MAX_VALUE) + 1024;
    lob.truncate(length);
    assertEquals(length, lob.length());
  }


  /*
   * Helper - uploads a file into a blob using old style methods. We use this because it always
   * works, and we can use it as a base to test the new methods.
   */
  private long uploadFile(String file, int method) throws Exception {
    try (InputStream fis = getClass().getResourceAsStream(file)) {
      String sql = String.format("INSERT INTO %s VALUES (?, ?)", "testblob");
      try (PreparedStatement ps = con.prepareStatement(sql)) {
        ps.setString(1, file);
        ps.setBlob(2, fis);
        ps.execute();
        con.commit();
      }
    }
    return 1;
  }

  /*
   * Helper - compares the blobs in a table with a local file. This uses the jdbc java.sql.Blob api
   */
  private boolean compareBlobs() throws Exception {
    boolean result = true;

    Statement st = con.createStatement();
    ResultSet rs = st.executeQuery(TestUtil.selectSQL("testblob", "id,lo"));
    assertNotNull(rs);

    while (rs.next()) {
      String file = rs.getString(1);
      Blob blob = rs.getBlob(2);

      InputStream fis = getClass().getResourceAsStream(file);
      InputStream bis = blob.getBinaryStream();

      int f = fis.read();
      int b = bis.read();
      int c = 0;
      while (f >= 0 && b >= 0 & result) {
        result = (f == b);
        f = fis.read();
        b = bis.read();
        c++;
      }
      result = result && f == -1 && b == -1;

      if (!result) {
        fail("JDBC API Blob compare failed at " + c + " of " + blob.length());
      }

      bis.close();
      fis.close();
    }
    rs.close();
    st.close();

    return result;
  }
}