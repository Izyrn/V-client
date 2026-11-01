package com.vclient;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.UUID;

/**
 * Captures the screen with the DXGI Desktop Duplication API, the same interface screen recorders use. Unlike a plain
 * GDI copy it also sees full-screen games (GDI keeps returning the last desktop picture, so clips came out as one
 * frozen image). The frame is copied out of the GPU once and shrunk on the CPU while it is read. All calls must come
 * from one thread, and it captures the primary monitor.
 */
final class DxgiGrabber implements AutoCloseable {
    private static final int S_OK = 0;
    private static final int WAIT_TIMEOUT = 0x887A0027;
    private static final int DXGI_FORMAT_B8G8R8A8_UNORM = 87;
    private static final int POINTER_SIZE = com.sun.jna.Native.POINTER_SIZE;
    /** Held for the life of the program: JNA unloads a library once its NativeLibrary object is garbage collected, which pulled Direct3D out from under the live capture objects. */
    private static final NativeLibrary D3D11 = NativeLibrary.getInstance("d3d11");

    private final Pointer device;
    private final Pointer context;
    private final Pointer duplication;
    private final int screenWidth;
    private final int screenHeight;
    private Pointer staging;
    private boolean hasFrame;
    private boolean lost;

    // Native scratch memory is allocated once and kept for the grabber's lifetime: allocating it for every frame let
    // the garbage collector free blocks while Direct3D could still touch them, which crashed the JVM.
    private final Memory frameInfo = new Memory(64);
    private final Memory mapped = new Memory(16);
    private final Memory iid = new Memory(16);
    private final PointerByReference resource = new PointerByReference();
    private final PointerByReference scratch = new PointerByReference();
    private int[] frameCopy = new int[0];

    DxgiGrabber() {
        PointerByReference deviceOut = new PointerByReference();
        PointerByReference contextOut = new PointerByReference();
        Function create = D3D11.getFunction("D3D11CreateDevice");
        int result = create.invokeInt(new Object[]{null, 1 /* hardware */, null, 0x20 /* BGRA support */, null, 0, 7,
                deviceOut, null, contextOut});
        if (result != S_OK) throw new IllegalStateException("No Direct3D device (0x" + Integer.toHexString(result) + ")");
        device = deviceOut.getValue();
        context = contextOut.getValue();
        Pointer dxgiDevice = null;
        Pointer adapter = null;
        Pointer output = null;
        Pointer output1 = null;
        try {
            dxgiDevice = queryInterface(device, "54ec77fa-1377-44e6-8c32-88fd5f44c84c");
            check(call(dxgiDevice, 7, scratch), "GetAdapter");
            adapter = scratch.getValue();
            check(call(adapter, 7, 0, scratch), "EnumOutputs");
            output = scratch.getValue();
            output1 = queryInterface(output, "00cddea8-939b-4b83-a340-a685226666cc");
            check(call(output1, 22, device, scratch), "DuplicateOutput");
            duplication = scratch.getValue();
            call(duplication, 7, frameInfo); // DXGI_OUTDUPL_DESC: ModeDesc.Width / Height come first
            screenWidth = frameInfo.getInt(0);
            screenHeight = frameInfo.getInt(4);
        } finally {
            release(output1);
            release(output);
            release(adapter);
            release(dxgiDevice);
        }
    }

    int screenWidth() {
        return screenWidth;
    }

    int screenHeight() {
        return screenHeight;
    }

    boolean lost() {
        return lost;
    }

    /**
     * The newest picture of {@code region} (in primary-monitor pixels) shrunk to {@code width} x {@code height}.
     * Returns the previous picture again when the screen has not changed, and null if nothing has been captured yet
     * or the duplication was lost (the caller then builds a new grabber).
     */
    BufferedImage grab(Rectangle region, int width, int height) {
        if (lost) return null;
        int result = call(duplication, 8, 0, frameInfo, resource);
        if (result == S_OK) {
            Pointer desktop = resource.getValue();
            try {
                Pointer texture = queryInterface(desktop, "6f15aaf2-d208-4e89-9ab4-489535d34f9c");
                try {
                    ensureStaging(texture);
                    call(context, 47, staging, texture); // CopyResource
                    hasFrame = true;
                } finally {
                    release(texture);
                }
            } finally {
                release(desktop);
                call(duplication, 14); // ReleaseFrame
            }
        } else if (result != WAIT_TIMEOUT) {
            lost = true; // access lost (display mode change, lock screen...) or another failure
            return null;
        }
        if (!hasFrame || staging == null) return null;

        if (call(context, 14, staging, 0, 1 /* D3D11_MAP_READ */, 0, mapped) != S_OK) return null;
    // Copy the needed rows out in one read and unmap straight away; shrinking happens from the Java copy.
    Pointer data = mapped.getPointer(0);
    int stride = mapped.getInt(8) / 4;
    int top = Math.max(0, Math.min(region.y, screenHeight - 1));
    int rows = Math.max(1, Math.min(region.height, screenHeight - top));
    if (frameCopy.length < rows * stride) frameCopy = new int[rows * stride];
    data.read((long) top * stride * 4, frameCopy, 0, rows * stride);
    call(context, 15, staging, 0); // Unmap
    return shrink(frameCopy, stride, region, width, height);
}

    /**
     * Shrinks by whole-pixel sampling with a horizontal two-pixel average. {@code pixels} holds the region's rows
     * starting at the region's first row, {@code stride} ints apart.
     */
    private BufferedImage shrink(int[] pixels, int stride, Rectangle region, int width, int height) {
        int left = Math.max(0, Math.min(region.x, screenWidth - 2));
        int top = Math.max(0, Math.min(region.y, screenHeight - 1));
        int regionWidth = Math.max(2, Math.min(region.width, screenWidth - left));
        int regionHeight = Math.max(1, Math.min(region.height, screenHeight - top));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] target = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        int[] columns = new int[width];
        for (int x = 0; x < width; x++) columns[x] = left + Math.min(regionWidth - 2, (int) ((long) x * regionWidth / width));
        for (int y = 0; y < height; y++) {
            int rowStart = Math.min(regionHeight - 1, (int) ((long) y * regionHeight / height)) * stride;
            int base = y * width;
            for (int x = 0; x < width; x++) {
                int source = rowStart + columns[x];
                // per-channel average of two neighbouring pixels without unpacking the channels
                target[base + x] = ((pixels[source] & 0xFEFEFE) >> 1) + ((pixels[source + 1] & 0xFEFEFE) >> 1);
            }
        }
        return image;
    }

    private void ensureStaging(Pointer source) {
        if (staging != null) return;
        // D3D11_TEXTURE2D_DESC: Width, Height, MipLevels, ArraySize, Format, Sample.Count, Sample.Quality, Usage,
        // BindFlags, CPUAccessFlags, MiscFlags (11 ints = 44 bytes)
        Memory desc = new Memory(44);
        call(source, 10, desc); // ID3D11Texture2D::GetDesc
        int format = desc.getInt(16) == 0 ? DXGI_FORMAT_B8G8R8A8_UNORM : desc.getInt(16);
        Memory full = new Memory(44);
        // Usage = STAGING (3), no bind flags, CPU read access (0x20000)
        full.write(0, new int[]{desc.getInt(0), desc.getInt(4), 1, 1, format, 1, 0, 3, 0, 0x20000, 0}, 0, 11);
        check(call(device, 5, full, null, scratch), "CreateTexture2D");
        staging = scratch.getValue();
    }

    // ---- COM plumbing --------------------------------------------------------------------------------

    private static int call(Pointer object, int slot, Object... arguments) {
        Pointer method = object.getPointer(0).getPointer((long) slot * POINTER_SIZE);
        Object[] all = new Object[arguments.length + 1];
        all[0] = object;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        return Function.getFunction(method).invokeInt(all);
    }

    private Pointer queryInterface(Pointer object, String guid) {
        UUID uuid = UUID.fromString(guid);
        long high = uuid.getMostSignificantBits();
        long low = uuid.getLeastSignificantBits();
        iid.setInt(0, (int) (high >>> 32));
        iid.setShort(4, (short) (high >>> 16));
        iid.setShort(6, (short) high);
        for (int i = 0; i < 8; i++) iid.setByte(8 + i, (byte) (low >>> (56 - 8 * i)));
        check(call(object, 0, iid, scratch), "QueryInterface");
        return scratch.getValue();
    }

    private static void release(Pointer object) {
        if (object != null) call(object, 2);
    }

    private static void check(int result, String what) {
        if (result != S_OK) throw new IllegalStateException(what + " failed (0x" + Integer.toHexString(result) + ")");
    }

    @Override
    public void close() {
        release(staging);
        release(duplication);
        release(context);
        release(device);
        staging = null;
    }
}
