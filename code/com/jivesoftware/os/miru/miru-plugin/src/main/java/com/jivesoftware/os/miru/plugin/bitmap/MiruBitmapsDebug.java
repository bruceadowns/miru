package com.jivesoftware.os.miru.plugin.bitmap;

import com.jivesoftware.os.miru.plugin.solution.MiruSolutionLog;
import com.jivesoftware.os.miru.plugin.solution.MiruSolutionLogLevel;

/**
 *
 */
public class MiruBitmapsDebug {

    public <BM> String toString(MiruBitmaps<BM> bitmaps, BM bitmap) {
        MiruIntIterator miruIntIterator = bitmaps.intIterator(bitmap);
        StringBuilder buf = new StringBuilder();
        while (miruIntIterator.hasNext()) {
            int id = miruIntIterator.next();
            buf.append(id).append('\n');
        }
        return buf.toString();
    }

    public <BM> void debug(MiruSolutionLog log, MiruBitmaps<BM> bitmaps, String message, Iterable<BM> iter) {
        if (log.isLogLevelEnabled(MiruSolutionLogLevel.INFO)) {
            StringBuilder buf = new StringBuilder(message);
            int i = 0;
            for (BM bitmap : iter) {
                buf.append("\n  ").append(++i).append('.')
                        .append(" cardinality=").append(bitmaps.cardinality(bitmap))
                        .append(" sizeInBits=").append(bitmaps.sizeInBits(bitmap))
                        .append(" sizeInBytes=").append(bitmaps.sizeInBytes(bitmap));
            }
            if (i == 0) {
                buf.append(" -0-");
            }
            buf.append('\n');
            log.log(MiruSolutionLogLevel.INFO, buf.toString());
        }
    }
}
