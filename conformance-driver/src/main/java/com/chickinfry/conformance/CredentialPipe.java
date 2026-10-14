package com.chickinfry.conformance;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

final class CredentialPipe extends InputStream {
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment buffer = arena.allocate(4096);
    private final MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
    private final long errnoOffset = Linker.Option.captureStateLayout().byteOffset(MemoryLayout.PathElement.groupElement("errno"));
    private final MethodHandle read;
    private final MethodHandle close;
    private long position;
    private long length;
    private boolean closed;

    CredentialPipe() throws IOException {
        String os = System.getProperty("os.name");
        if (!(os.equals("Mac OS X") || os.equals("Linux")) || ADDRESS.byteSize() != 8) throw new IOException("Unsupported credential pipe.");
        Linker linker = Linker.nativeLinker();
        read = linker.downcallHandle(linker.defaultLookup().find("read").orElseThrow(),
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), Linker.Option.captureCallState("errno"));
        close = linker.downcallHandle(linker.defaultLookup().find("close").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    }

    @Override public int read() throws IOException {
        if (closed) throw new IOException("Credential pipe closed.");
        if (position == length) {
            try {
                do {
                    length = (long) read.invokeExact(state, 3, buffer, 4096L);
                } while (length == -1 && state.get(JAVA_INT, errnoOffset) == 4);
            } catch (Throwable failure) { throw new IOException("Credential pipe read failed."); }
            if (length < 0) throw new IOException("Credential pipe read failed.");
            position = 0;
            if (length == 0) return -1;
        }
        return Byte.toUnsignedInt(buffer.get(JAVA_BYTE, position++));
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            int result = (int) close.invokeExact(3);
            if (result != 0) throw new IOException("Credential pipe close failed.");
        } catch (Throwable failure) { throw new IOException("Credential pipe close failed."); }
        finally { arena.close(); }
    }
}
