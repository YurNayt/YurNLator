package com.winlator.cmod.xconnector;

import com.winlator.cmod.xserver.XServer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.locks.ReentrantLock;

public class XOutputStream {
    private static final byte[] ZERO = new byte[64];
    public ByteBuffer buffer;
    public final ClientSocket clientSocket;
    private final ReentrantLock lock = new ReentrantLock();
    private int ancillaryFd = -1;

    public XOutputStream(int initialCapacity) {
        this(null, initialCapacity);
    }

    public XOutputStream(ClientSocket clientSocket, int initialCapacity) {
        this.clientSocket = clientSocket;
        buffer = ByteBuffer.allocateDirect(initialCapacity);
    }

    public void setByteOrder(ByteOrder byteOrder) {
        buffer.order(byteOrder);
    }

    public void setAncillaryFd(int ancillaryFd) {
        this.ancillaryFd = ancillaryFd;
    }

    public void writeByte(byte value) {
        ensureSpaceIsAvailable(1);
        buffer.put(value);
    }

    public void writeShort(short value) {
        ensureSpaceIsAvailable(2);
        buffer.putShort(value);
    }

    public void writeInt(int value) {
        ensureSpaceIsAvailable(4);
        buffer.putInt(value);
    }

    public void writeIntPair(int first, int second) {
        writeInt(first);
        writeInt(second);
    }

    public void writeLong(long value) {
        ensureSpaceIsAvailable(8);
        buffer.putLong(value);
    }

    public void writeFP3232(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("FP3232 value must be finite");
        }

        long fixed = Math.round(value * 4294967296.0);
        writeInt((int)(fixed >> 32));
        writeInt((int)fixed);
    }

    public void writeString8(String str) {
        byte[] bytes = str.getBytes(XServer.LATIN1_CHARSET);
        int length = -str.length() & 3;
        ensureSpaceIsAvailable(bytes.length + length);
        buffer.put(bytes);
        if (length > 0) writePad(length);
    }

    public void write(byte[] data) {
        write(data, 0, data.length);
    }

    public void write(byte[] data, int offset, int length) {
        ensureSpaceIsAvailable(length);
        buffer.put(data, offset, length);
    }

    public void write(ByteBuffer data) {
        ensureSpaceIsAvailable(data.remaining());
        buffer.put(data);
    }

    public void writePad(int length) {
        write(ZERO, 0, length);
    }

    private void flush() throws IOException {
        if (buffer.position() == 0) return;
        buffer.flip();
        try {
            if (closed || clientSocket == null) return;
            if (ancillaryFd != -1) {
                // fds must travel with their own bytes, so earlier data has to be out first (client thread only)
                while (pendingBytes() > 0 && !closed) {
                    if (sendPending() < 0) break;
                    if (pendingBytes() > 0 && clientSocket.waitWritable(1000) < 0) { discardPending(); break; }
                }
                clientSocket.sendAncillaryMsg(buffer, ancillaryFd);
                ancillaryFd = -1;
                return;
            }
            if (pendingBytes() > 0) sendPending();
            if (pendingBytes() == 0) {
                if (clientSocket.sendNonBlocking(buffer) < 0 || !buffer.hasRemaining()) return;
            }
            // the client is not reading right now: keep the rest in order and never block the caller
            appendPending(buffer);
            if (Thread.currentThread() != ownerThread) PendingFlusher.add(this);
        } finally {
            buffer.clear();
        }
    }

    /*
     * A client that stops reading (a Wine thread stuck on a slow drive, say) must not freeze whoever
     * sends it events: injected touch input runs on the UI thread, and a blocking write there is an
     * ANR. Bytes the socket cannot take go to {@code pending}; the client's own thread waits for room
     * after releasing the lock (back-pressure for big replies), everyone else leaves it to the flusher.
     */
    private static final int PENDING_LIMIT = 64 << 20;
    private ByteBuffer pending;
    private volatile boolean closed;
    private volatile Thread ownerThread;

    void setOwnerThread(Thread thread) {
        ownerThread = thread;
    }

    /** Called before the fd is closed, so no queued byte can reach a reused descriptor. */
    void markClosed() {
        lock.lock();
        try {
            closed = true;
            discardPending();
        } finally {
            lock.unlock();
        }
    }

    private int pendingBytes() {
        return pending != null ? pending.position() : 0;
    }

    private void discardPending() {
        if (pending != null) pending.clear();
    }

    private int sendPending() {
        pending.flip();
        int n = clientSocket.sendNonBlocking(pending);
        pending.compact();
        if (n < 0) discardPending();
        return n;
    }

    private void appendPending(ByteBuffer data) {
        int length = data.remaining();
        if (pendingBytes() + length > PENDING_LIMIT) {
            android.util.Log.w("XOutputStream", "client fd " + clientSocket.fd + " stopped reading, dropping its queue");
            discardPending();
            closed = true;
            return;
        }
        if (pending == null || pending.capacity() - pending.position() < length) {
            int capacity = Math.max(64 * 1024, Integer.highestOneBit(pendingBytes() + length) << 1);
            ByteBuffer grown = ByteBuffer.allocateDirect(capacity);
            if (pending != null) {
                pending.flip();
                grown.put(pending);
            }
            pending = grown;
        }
        pending.put(data);
    }

    /** Flusher thread: true once nothing is left for this stream. Never waits for the lock. */
    boolean drainFromFlusher() {
        if (!lock.tryLock()) return false;
        try {
            if (closed || pendingBytes() == 0) return true;
            return sendPending() < 0 || pendingBytes() == 0;
        } finally {
            lock.unlock();
        }
    }

    void waitForRoom(int timeoutMs) throws InterruptedException {
        if (closed || clientSocket.waitWritable(timeoutMs) == 0) return;
        Thread.sleep(1);
    }

    private void drainAsOwner() {
        for (;;) {
            lock.lock();
            try {
                if (closed || pendingBytes() == 0) return;
                if (sendPending() < 0 || pendingBytes() == 0) return;
            } finally {
                lock.unlock();
            }
            if (clientSocket.waitWritable(1000) < 0) {
                markClosed();
                return;
            }
        }
    }

    public XStreamLock lock() {
        return new OutputStreamLock();
    }

    private void ensureSpaceIsAvailable(int length) {
        int position = buffer.position();
        if ((buffer.capacity() - position) >= length) return;
        ByteBuffer newBuffer = ByteBuffer.allocateDirect(buffer.capacity() + length).order(buffer.order());
        buffer.rewind();
        newBuffer.put(buffer).position(position);
        buffer = newBuffer;
    }

    private class OutputStreamLock implements XStreamLock {
        public OutputStreamLock() {
            lock.lock();
        }

        @Override
        public void close() throws IOException {
            boolean waitForRoom;
            try {
                flush();
                waitForRoom = pendingBytes() > 0 && Thread.currentThread() == ownerThread;
            } finally {
                lock.unlock();
            }
            if (waitForRoom) drainAsOwner();
        }
    }

    public void writeSuccessReply(int sequenceNumber, int replyLength) throws IOException {
        try (XStreamLock lock = lock()) {
            writeByte((byte) 1);
            writeByte((byte) 0);
            writeShort((short) sequenceNumber);
            writeInt(replyLength);
            writePad(24);
        }
    }
}
