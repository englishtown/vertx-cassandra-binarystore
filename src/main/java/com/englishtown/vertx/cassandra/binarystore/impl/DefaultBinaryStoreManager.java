package com.englishtown.vertx.cassandra.binarystore.impl;

import com.codahale.metrics.MetricRegistry;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.englishtown.vertx.cassandra.CassandraSession;
import com.englishtown.vertx.cassandra.binarystore.*;
import com.google.common.util.concurrent.FutureCallback;

import javax.inject.Inject;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Default implementation of {@link com.englishtown.vertx.cassandra.binarystore.BinaryStoreManager}
 */
public class DefaultBinaryStoreManager implements BinaryStoreManager {

    private final MetricRegistry registry;

    private final CassandraSession session;
    private final BinaryStoreStatements statements;
    private final Metrics fileMetrics;
    private final Metrics chunkMetrics;

    @Inject
    public DefaultBinaryStoreManager(CassandraSession session, BinaryStoreStatements statements, MetricRegistry registry) {
        this.session = session;
        this.statements = statements;
        this.registry = registry;

        this.fileMetrics = new Metrics(registry, "files");
        this.chunkMetrics = new Metrics(registry, "chunks");
    }

    @Override
    public void storeFile(FileInfo fileInfo, final FutureCallback<Void> callback) {

        final Metrics.Context context = fileMetrics.timeWrite();

        BoundStatement insert = statements
                .getStoreFile()
                .bind(
                        fileInfo.getId(),
                        fileInfo.getLength(),
                        fileInfo.getChunkSize(),
                        fileInfo.getUploadDate(),
                        fileInfo.getFileName(),
                        fileInfo.getContentType(),
                        fileInfo.getMetadata()
                );

        session.executeAsync(insert, new FutureCallback<ResultSet>() {
            @Override
            public void onSuccess(ResultSet result) {
                if (context != null) context.stop();

                callback.onSuccess(null);
            }

            @Override
            public void onFailure(Throwable t) {
                if (context != null) context.error();

                callback.onFailure(t);
            }
        });

    }

    @Override
    public void storeChunk(ChunkInfo chunkInfo, final FutureCallback<Void> callback) {

        final Metrics.Context context = chunkMetrics.timeWrite();

        BoundStatement insert = statements
                .getStoreChunk()
                .bind(
                        chunkInfo.getId(),
                        chunkInfo.getNum(),
                        ByteBuffer.wrap(chunkInfo.getData())
                );

        session.executeAsync(insert, new FutureCallback<ResultSet>() {
            @Override
            public void onSuccess(ResultSet result) {
                if (context != null) context.stop();

                callback.onSuccess(null);
            }

            @Override
            public void onFailure(Throwable t) {
                if (context != null) context.error();

                callback.onFailure(t);
            }
        });

    }

    @Override
    public void loadFile(final UUID id, final FutureCallback<FileInfo> callback) {

        final Metrics.Context context = fileMetrics.timeRead();

        BoundStatement select = statements.getLoadFile().bind(id);
        session.executeAsync(select, new FutureCallback<ResultSet>() {
            @Override
            public void onSuccess(ResultSet result) {
                Row row = result.one();
                if (row == null) {
                    context.stop();
                    callback.onSuccess(null);
                    return;
                }

                try {
                    DefaultFileInfo fileInfo = new DefaultFileInfo()
                            .setId(id)
                            .setFileName(row.getString("filename"))
                            .setContentType(row.getString("contentType"))
                            .setLength(row.getLong("length"))
                            .setChunkSize(row.getInt("chunkSize"))
                            .setUploadDate(row.getLong("uploadDate"))
                            .setMetadata(row.getMap("metadata", String.class, String.class));

                    context.stop();
                    callback.onSuccess(fileInfo);

                } catch (Throwable t) {
                    context.error();
                    callback.onFailure(t);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                context.error();
                callback.onFailure(t);
            }
        });
    }

    @Override
    public void loadChunk(final UUID id, final int n, final FutureCallback<ChunkInfo> callback) {

        final Metrics.Context context = chunkMetrics.timeRead();

        BoundStatement select = statements.getLoadChunk().bind(id, n);
        session.executeAsync(select, new FutureCallback<ResultSet>() {
            @Override
            public void onSuccess(ResultSet result) {
                Row row = result.one();
                if (row == null) {
                    context.stop();
                    callback.onSuccess(null);
                    return;
                }

                try {
                    DefaultChunkInfo chunkInfo = new DefaultChunkInfo()
                            .setId(id)
                            .setNum(n);

                    ByteBuffer bb = row.getBytes("data");
                    byte[] data = new byte[bb.remaining()];
                    bb.get(data);
                    chunkInfo.setData(data);

                    context.stop();
                    callback.onSuccess(chunkInfo);

                } catch (Throwable t) {
                    context.error();
                    callback.onFailure(t);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                context.error();
                callback.onFailure(t);
            }
        });

    }
}
