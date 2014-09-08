package com.jivesoftware.os.miru.query;

import com.jivesoftware.os.jive.utils.logger.MetricLogger;

/**
 *
 */
public class MiruBitmapsDebug {

    public <BM> void debug(MetricLogger log, MiruBitmaps<BM> bitmaps, String message, Iterable<BM> iter) {
        if (log.isDebugEnabled()) {
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
            log.debug(buf.toString());
        }
        if (log.isTraceEnabled()) {
            StringBuilder buf = new StringBuilder(message);
            int i = 0;
            for (BM bitmap : iter) {
                buf.append("\n  ").append(++i).append('.')
                        .append(" bits=").append(bitmap);
            }
            if (i == 0) {
                buf.append(" -0-");
            }
            log.trace(buf.toString());
        }
    }
}
