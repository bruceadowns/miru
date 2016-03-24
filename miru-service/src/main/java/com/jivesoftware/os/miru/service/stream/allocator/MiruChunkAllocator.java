package com.jivesoftware.os.miru.service.stream.allocator;

import com.jivesoftware.os.filer.io.api.StackBuffer;
import com.jivesoftware.os.filer.io.chunk.ChunkStore;
import com.jivesoftware.os.lab.LABEnvironment;
import com.jivesoftware.os.miru.api.MiruPartitionCoord;
import java.io.File;

/**
 *
 */
public interface MiruChunkAllocator {

    boolean checkExists(MiruPartitionCoord coord) throws Exception;

    ChunkStore[] allocateChunkStores(MiruPartitionCoord coord, StackBuffer stackBuffer) throws Exception;

    void close(ChunkStore[] chunkStores);

    File[] getLabDirs(MiruPartitionCoord coord) throws Exception;

    LABEnvironment[] allocateLABEnvironments(File[] labDirs) throws Exception;
}
