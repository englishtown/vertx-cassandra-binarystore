package com.englishtown.vertx.cassandra.binarystore.impl;

import com.englishtown.vertx.cassandra.binarystore.*;
import com.google.common.primitives.Ints;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.UUID;

/**
 * Default implementation of {@link com.englishtown.vertx.cassandra.binarystore.BinaryStoreReader}
 */
public class DefaultBinaryStoreReader implements BinaryStoreReader {

    private final BinaryStoreManager binaryStoreManager;
    private static final Logger logger = LoggerFactory.getLogger(DefaultBinaryStoreReader.class);
    ;

    @Inject
    public DefaultBinaryStoreReader(BinaryStoreManager binaryStoreManager) {
        this.binaryStoreManager = binaryStoreManager;
    }

    @Override
    public FileReader read(UUID id) {
        return innerRead(id, null);
    }

    @Override
    public FileReader readRange(UUID id, ContentRange range) {
        return innerRead(id, range);
    }

    private FileReader innerRead(UUID id, final ContentRange range) {

        final FileReader reader = new FileReader();

        binaryStoreManager.loadFile(id)
                .then(fileInfo -> {
                    if (fileInfo == null) {
                        reader.handleEnd(FileReader.Result.NOT_FOUND);
                        return null;
                    }

                    if (range == null) {
                        reader.handleFile(new FileReadInfo().setFile(fileInfo));
                        loadChunks(0, fileInfo.getChunkCount(), fileInfo, reader);
                    } else {
                        RangeInfo rangeInfo = new RangeInfo(range, fileInfo);
                        ContentRange updatedRange = new ContentRange()
                                .setFrom(rangeInfo.getFrom())
                                .setTo(rangeInfo.getTo());

                        reader.handleFile(new FileReadInfo().setFile(fileInfo).setRange(updatedRange));
                        loadRangeChunks(rangeInfo.getStartChunk(), rangeInfo, fileInfo, reader);
                    }

                    return null;
                })
                .otherwise(t -> {
                    reader.handleException(t);
                    return null;
                });

        return reader;

    }

    private void loadChunks(final int n, final int count, final FileInfo fileInfo, final FileReader reader) {

        if (n == count) {
            reader.handleEnd(FileReader.Result.OK);
            return;
        }

        if (reader.isPaused()) {
            reader.resumeHandler(event -> loadChunks(n, count, fileInfo, reader));
            return;
        }

        binaryStoreManager.loadChunk(fileInfo.getId(), n)
                .then(chunkInfo -> {
                    if (chunkInfo != null) {
                        reader.handleData(chunkInfo.getData());
                        loadChunks(n + 1, count, fileInfo, reader);
                    } else {
                        reader.handleEnd(FileReader.Result.OK);
                    }
                    return null;
                })
                .otherwise(t -> {
                    logger.error("Error loading chunk", t);
                    reader.handleEnd(FileReader.Result.ERROR);
                    return null;
                });
    }

    private void loadRangeChunks(
            final int n,
            final RangeInfo rangeInfo,
            final FileInfo fileInfo,
            final FileReader reader
    ) {

        if (n > rangeInfo.getEndChunk()) {
            reader.handleEnd(FileReader.Result.OK);
            return;
        }

        binaryStoreManager.loadChunk(fileInfo.getId(), n)
                .then(chunkInfo -> {
                    if (chunkInfo == null) {
                        Throwable t = new Throwable("Error while reading chunk " + n + ". It came back as null.");
                        reader.handleException(t);
                        reader.handleEnd(FileReader.Result.ERROR);
                    } else {
                        reader.handleData(rangeInfo.getRequiredBytesFromChunk(n, chunkInfo.getData()));
                        loadRangeChunks(n + 1, rangeInfo, fileInfo, reader);
                    }
                    return null;
                })
                .otherwise(t -> {
                    reader.handleException(t);
                    reader.handleEnd(FileReader.Result.ERROR);
                    return null;
                });

    }

    private static class RangeInfo {

        private final int startChunk;
        private final int endChunk;

        private final int startPos;
        private final int endPos;

        private final long from;
        private final long to;


        public RangeInfo(ContentRange range, FileInfo fileInfo) throws IllegalArgumentException {

            long from = range.getFrom();
            long to = fileInfo.getLength() - 1;
            if (range.getTo() >= 0 && range.getTo() < to) {
                to = range.getTo();
            }

            int chunkSize = fileInfo.getChunkSize();
            startChunk = Ints.checkedCast(from / chunkSize);
            endChunk = Ints.checkedCast(to / chunkSize);

            startPos = Ints.checkedCast(from - (startChunk * chunkSize));
            endPos = Ints.checkedCast(to - (endChunk * chunkSize));

            this.from = from;
            this.to = to;
        }

        public byte[] getRequiredBytesFromChunk(int chunkNumber, byte[] chunk) {

            /*
             * The rules are: 1. If this is the start chunk, but not the end chunk, then we want bytes: startpos - chunk_length
             *                2. If this is the end chunk, but not the start chunk, then we want bytes: 0 - (endpos+1)
             *                3. If this is both the start and end chunk then we want bytes: startpos - (endpos+1)
             *                4. If it's none of these then we want the whole chunk
             *
             * The +1s are there because Arrays.copyOfRange end position parameter is exclusive.
             */

            // If this is the start chunk and not the end chunk, we want to take all the bytes from start position to the end of the chunk
            if (chunkNumber == startChunk && chunkNumber != endChunk) {
                return Arrays.copyOfRange(chunk, startPos, chunk.length);
            }


            // If this is the end chunk and not also the start chunk, we want to take all of the bytes from the beginning of the chunk up to the end.
            if (chunkNumber == endChunk && chunkNumber != startChunk) {
                return Arrays.copyOfRange(chunk, 0, endPos + 1);
            }

            // In the instance that this is *both* the start and end chunk, then we want to only return the bytes between start and end pos
            if (chunkNumber == startChunk && chunkNumber == endChunk) {
                return Arrays.copyOfRange(chunk, startPos, endPos + 1);
            }

            // Finally, we get here if this chunk is neither a start or end chunk, in which case we want the whole thing.
            return chunk;
        }

        public int getStartChunk() {
            return startChunk;
        }

        public int getEndChunk() {
            return endChunk;
        }

        public long getFrom() {
            return from;
        }

        public long getTo() {
            return to;
        }

    }
}
