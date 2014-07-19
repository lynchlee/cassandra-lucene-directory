package org.opennms.newts.search;


import static com.datastax.driver.core.querybuilder.QueryBuilder.eq;
import static com.datastax.driver.core.querybuilder.QueryBuilder.gte;
import static com.datastax.driver.core.querybuilder.QueryBuilder.insertInto;
import static com.datastax.driver.core.querybuilder.QueryBuilder.lte;
import static com.datastax.driver.core.querybuilder.QueryBuilder.select;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.opennms.newts.search.CassandraConstants.C_FILES_DATA;
import static org.opennms.newts.search.CassandraConstants.C_FILES_ID;
import static org.opennms.newts.search.CassandraConstants.C_FILES_INDEX;
import static org.opennms.newts.search.CassandraConstants.C_FILES_INDEX_ID;
import static org.opennms.newts.search.CassandraConstants.C_FILES_INDEX_INDEX;
import static org.opennms.newts.search.CassandraConstants.C_FILES_INDEX_NAME;
import static org.opennms.newts.search.CassandraConstants.C_FILES_SEGMENT;
import static org.opennms.newts.search.CassandraConstants.T_FILES;
import static org.opennms.newts.search.CassandraConstants.T_FILES_INDEX;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Statement;
import com.google.common.collect.Lists;

// XXX: Not threadsafe. At all.
class CassandraFile {

    static int SEGMENT_SIZE = 4096;

    static enum Mode {
        CREATE, READ;
    }

    private CassandraSession m_session;
    private UUID m_id;
    private String m_name;
    private Mode m_mode;
    private long m_length;
    private int m_numSegments;
    private ByteBuffer m_buffer = ByteBuffer.allocate(SEGMENT_SIZE);
    private int m_segmentPointer = 1;
    private boolean m_isClosed = false;

    CassandraFile(CassandraSession session, String name, Mode mode) throws IOException {
        m_session = checkNotNull(session, "session argument");
        m_name = checkNotNull(name, "name argument");
        m_mode = checkNotNull(mode, "mode argument");

        switch (m_mode) {
            case CREATE:
                initCreate();
                break;

            case READ:
                initRead();
                break;

            default:
                throw new IllegalArgumentException("Unknown mode; Bugged!!");
        }

    }

    private void initCreate() {
        m_id = UUID.randomUUID();
        m_length = 0;
        m_numSegments = 0;
    }

    private void initRead() throws IOException {

        // XXX: This could be a prepared statement
        Statement query = select(C_FILES_INDEX_ID).from(T_FILES_INDEX)
                .where( eq(C_FILES_INDEX_INDEX,  m_session.getIndexName()))
                .and  ( eq(C_FILES_INDEX_NAME,   m_name));

        ResultSet resultSet = m_session.execute(query);
        Row row = resultSet.one();

        if (row != null) {
            m_id = row.getUUID(C_FILES_INDEX_ID);

            ByteBuffer[] slice = getSegmentSlice(m_id, 0, 0);

            if (slice.length > 0) {
                m_length = slice[0].getLong();
                m_numSegments = slice[0].getInt();
            }
            else {
                throw new IOException(String.format("Cannot read manifest for: %s (%s)", m_id, m_name));
            }
        }
        else {
            throw new FileNotFoundException(m_name);
        }

    }

    private ByteBuffer[] getSegmentSlice(UUID id, int from, int to) {

        // XXX: This could be a prepared statement
        Statement query = select(C_FILES_DATA).from(T_FILES)
                .where(  eq(C_FILES_INDEX,    m_session.getIndexName()))
                .and  (  eq(C_FILES_ID,       id))
                .and  ( gte(C_FILES_SEGMENT,  from))
                .and  ( lte(C_FILES_SEGMENT,  to));

        List<ByteBuffer> results = Lists.newArrayList();

        for (Row row : m_session.execute(query)) {
            results.add(row.getBytes(C_FILES_DATA));
        }

        return results.toArray(new ByteBuffer[] {});
    }

    long getLength() {
        return m_length;
    }

    int getNumSegments() {
        return m_numSegments;
    }

    String getResourceDescription() {
        return getResourceDescription(m_session, m_name);
    }

    private void ensureWriteable() {
        checkState(m_mode.equals(Mode.CREATE), "illegal mode (%s) for writing", m_mode);
        checkState(!m_isClosed, "instance is closed, (open another)");
    }

    // Write the contents of buffer to Cassandra as a new segment
    private void syncBuffer() {
        ensureWriteable();

        m_buffer.flip();
        
        m_session.execute(
                insertInto(T_FILES)
                    .value(C_FILES_INDEX, m_session.getIndexName())
                    .value(C_FILES_ID, m_id)
                    .value(C_FILES_SEGMENT, m_segmentPointer)
                    .value(C_FILES_DATA, m_buffer)
        );

        m_segmentPointer += 1;
        m_numSegments += 1;
        m_buffer.clear();

    }

    private Buffer getManifest() {
        return ByteBuffer.allocate((Integer.SIZE / 8) + (Long.SIZE / 8)).putLong(m_length).putInt(m_numSegments + 1).flip();
    }

    void writeBytes(byte[] b, int offset, int length) throws IOException {
        ensureWriteable();

        int remaining = m_buffer.remaining();

        if (remaining > length) {
            m_length += length;
            m_buffer.put(b, offset, length);
        }
        else {
            m_length += remaining;
            m_buffer.put(b, offset, remaining);
            syncBuffer();
            writeBytes(b, offset + remaining, b.length - remaining);
        }

    }

    void close() {

        // Flush whats left in the buffer
        syncBuffer();

        // Write segment zero
        m_session.execute(
                insertInto(T_FILES)
                    .value(C_FILES_INDEX,  m_session.getIndexName())
                    .value(C_FILES_ID, m_id)
                    .value(C_FILES_SEGMENT, 0)
                    .value(C_FILES_DATA, (ByteBuffer)getManifest()) 
        );

        // Commit to inverted index
        m_session.execute(
                insertInto(T_FILES_INDEX)
                    .value(C_FILES_INDEX_INDEX, m_session.getIndexName())
                    .value(C_FILES_INDEX_NAME, m_name)
                    .value(C_FILES_INDEX_ID, m_id)
        );

    }

    static String[] listAll(CassandraSession session) throws IOException {

        List<String> results = Lists.newArrayList();

        // XXX: This could be a prepared statement
        Statement s = select(C_FILES_INDEX_NAME).from(T_FILES_INDEX).where(eq(C_FILES_INDEX_INDEX, session.getIndexName()));

        for (Row row : session.execute(s)) {
            results.add(row.getString(CassandraConstants.C_FILES_INDEX_ID));
        }

        return results.toArray(new String[] {});
    }

    static boolean exists(CassandraSession session, String name) throws IOException {
        return Arrays.asList(listAll(session)).contains(name);
    }

    static void deleteFile(CassandraSession session, String name) throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException();
    }

    static long fileLength(CassandraSession session, String name) throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException();
    }

    static String getResourceDescription(CassandraSession session, String name) {
        return String.format("/%s/%s", session.getIndexName(), name);
    }

}
