package com.dr.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class SQLiteVectorStore {
    private static final Logger LOG = LoggerFactory.getLogger(SQLiteVectorStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String jdbcUrl;

    public SQLiteVectorStore(Path workspaceRoot) {
        this.jdbcUrl = "jdbc:sqlite:" + workspaceRoot.resolve(".dongrancli_rag.db").toAbsolutePath();
        init();
    }

    private void init() {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS chunks(
                      id TEXT PRIMARY KEY,
                      path TEXT NOT NULL,
                      chunk_type TEXT NOT NULL,
                      symbol TEXT,
                      start_line INTEGER,
                      end_line INTEGER,
                      content TEXT NOT NULL,
                      keywords TEXT,
                      embedding TEXT,
                      updated_at INTEGER
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS relations(
                      src TEXT NOT NULL,
                      rel_type TEXT NOT NULL,
                      dst TEXT NOT NULL
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_chunks_path ON chunks(path)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_rel_src ON relations(src)");
        } catch (Exception e) {
            LOG.warn("初始化 SQLite 失败", e);
        }
    }

    public void replaceAll(List<StoredChunk> chunks, List<RelationEdge> relations) {
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            c.setAutoCommit(false);
            try (Statement clear = c.createStatement()) {
                clear.execute("DELETE FROM chunks");
                clear.execute("DELETE FROM relations");
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO chunks(id,path,chunk_type,symbol,start_line,end_line,content,keywords,embedding,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """)) {
                long now = System.currentTimeMillis();
                for (StoredChunk ch : chunks) {
                    ps.setString(1, ch.chunk().id());
                    ps.setString(2, ch.chunk().path());
                    ps.setString(3, ch.chunk().chunkType().name());
                    ps.setString(4, ch.chunk().symbol());
                    ps.setInt(5, ch.chunk().startLine());
                    ps.setInt(6, ch.chunk().endLine());
                    ps.setString(7, ch.chunk().content());
                    ps.setString(8, MAPPER.writeValueAsString(ch.chunk().keywords()));
                    ps.setString(9, MAPPER.writeValueAsString(ch.embedding()));
                    ps.setLong(10, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO relations(src,rel_type,dst) VALUES(?,?,?)")) {
                for (RelationEdge r : relations) {
                    ps.setString(1, r.src());
                    ps.setString(2, r.relationType());
                    ps.setString(3, r.dst());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (Exception e) {
            LOG.warn("写入 SQLite 向量库失败", e);
        }
    }

    public List<StoredChunk> loadAllChunks() {
        List<StoredChunk> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM chunks")) {
            while (rs.next()) {
                CodeChunk chunk = new CodeChunk(
                        rs.getString("id"),
                        rs.getString("path"),
                        ChunkType.valueOf(rs.getString("chunk_type")),
                        rs.getString("symbol"),
                        rs.getInt("start_line"),
                        rs.getInt("end_line"),
                        rs.getString("content"),
                        MAPPER.readValue(rs.getString("keywords"), new TypeReference<>() {
                        })
                );
                List<Double> embedding = MAPPER.readValue(rs.getString("embedding"), new TypeReference<>() {
                });
                out.add(new StoredChunk(chunk, embedding));
            }
        } catch (Exception e) {
            LOG.warn("读取 SQLite 向量库失败", e);
        }
        return out;
    }

    public List<RelationEdge> loadRelations() {
        List<RelationEdge> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT src,rel_type,dst FROM relations")) {
            while (rs.next()) {
                out.add(new RelationEdge(rs.getString("src"), rs.getString("rel_type"), rs.getString("dst")));
            }
        } catch (Exception e) {
            LOG.warn("读取关系图失败", e);
        }
        return out;
    }

    public record StoredChunk(CodeChunk chunk, List<Double> embedding) {
    }
}
