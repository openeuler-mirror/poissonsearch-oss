package org.elasticsearch.common.compress.lzf;

import java.io.IOException;
import java.io.OutputStream;

/**
 * @author jon hartlaub
 * @author tatu
 */
public class LZFOutputStream extends OutputStream {
    private static int OUTPUT_BUFFER_SIZE = LZFChunk.MAX_CHUNK_LEN;

    private final ChunkEncoder _encoder;
    private final BufferRecycler _recycler;

    protected final OutputStream _outputStream;
    protected byte[] _outputBuffer;
    protected int _position = 0;

    /**
     * Flag that indicates if we have already called '_outputStream.close()'
     * (to avoid calling it multiple times)
     */
    protected boolean _outputStreamClosed;

    /*
   ///////////////////////////////////////////////////////////////////////
   // Construction
   ///////////////////////////////////////////////////////////////////////
    */

    public LZFOutputStream(final OutputStream outputStream) {
        _recycler = BufferRecycler.instance();
        _encoder = new ChunkEncoder(OUTPUT_BUFFER_SIZE, _recycler);
        _outputStream = outputStream;
        _outputBuffer = _recycler.allocOutputBuffer(OUTPUT_BUFFER_SIZE);
        _outputStreamClosed = false;
    }

    /*
    ///////////////////////////////////////////////////////////////////////
    // OutputStream impl
    ///////////////////////////////////////////////////////////////////////
     */

    @Override
    public void write(final int singleByte) throws IOException {
        if (_position >= _outputBuffer.length) {
            writeCompressedBlock();
        }
        _outputBuffer[_position++] = (byte) singleByte;
    }

    @Override
    public void write(final byte[] buffer, int offset, int length) throws IOException {
        final int BUFFER_LEN = _outputBuffer.length;

        // simple case first: buffering only (for trivially short writes)
        int free = BUFFER_LEN - _position;
        if (free >= length) {
            System.arraycopy(buffer, offset, _outputBuffer, _position, length);
            _position += length;
            return;
        }
        // otherwise, copy whatever we can, flush
        System.arraycopy(buffer, offset, _outputBuffer, _position, free);
        offset += free;
        length -= free;
        _position += free;
        writeCompressedBlock();

        // then write intermediate full block, if any, without copying:
        while (length >= BUFFER_LEN) {
            _encoder.encodeAndWriteChunk(buffer, offset, BUFFER_LEN, _outputStream);
            offset += BUFFER_LEN;
            length -= BUFFER_LEN;
        }

        // and finally, copy leftovers in buffer, if any
        if (length > 0) {
            System.arraycopy(buffer, offset, _outputBuffer, 0, length);
        }
        _position = length;
    }

    @Override
    public void flush() throws IOException {
        if (_position > 0) {
            writeCompressedBlock();
        }
        _outputStream.flush();
    }

    @Override
    public void close() throws IOException {
        flush();
        _encoder.close();
        byte[] buf = _outputBuffer;
        if (buf != null) {
            _outputBuffer = null;
            _recycler.releaseOutputBuffer(buf);
        }
        if (!_outputStreamClosed) {
            _outputStreamClosed = true;
            _outputStream.close();
        }
    }

    /*
    ///////////////////////////////////////////////////////////////////////
    // Additional public accessors
    ///////////////////////////////////////////////////////////////////////
     */

    /**
     * Method that can be used to find underlying {@link OutputStream} that
     * we write encoded LZF encoded data into, after compressing it.
     * Will never return null; although underlying stream may be closed
     * (if this stream has been closed).
     *
     * @since 0.8
     */
    public OutputStream getUnderlyingOutputStream() {
        return _outputStream;
    }

    /*
   ///////////////////////////////////////////////////////////////////////
   // Internal methods
   ///////////////////////////////////////////////////////////////////////
    */

    /**
     * Compress and write the current block to the OutputStream
     */
    protected void writeCompressedBlock() throws IOException {
        int left = _position;
        _position = 0;
        int offset = 0;

        do {
            int chunkLen = Math.min(LZFChunk.MAX_CHUNK_LEN, left);
            _encoder.encodeAndWriteChunk(_outputBuffer, 0, chunkLen, _outputStream);
            offset += chunkLen;
            left -= chunkLen;
        } while (left > 0);
    }
}
