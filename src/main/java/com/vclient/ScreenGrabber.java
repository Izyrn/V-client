package com.vclient;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Copies a screen region straight into a small bitmap with GDI. Letting Windows shrink the picture while it copies
 * is several times faster than java.awt.Robot (which copies the full-size image and converts it in Java), which keeps
 * the replay buffer's frame rate up and its CPU use down. One instance is used by one thread.
 */
final class ScreenGrabber implements AutoCloseable {
    private interface Gdi extends StdCallLibrary {
        Gdi INSTANCE = Native.load("gdi32", Gdi.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean StretchBlt(WinDef.HDC destination, int x, int y, int width, int height, WinDef.HDC source,
                           int sourceX, int sourceY, int sourceWidth, int sourceHeight, int operation);

        int SetStretchBltMode(WinDef.HDC context, int mode);
    }

    private static final int SRCCOPY = 0x00CC0020;
    private static final int CAPTUREBLT = 0x40000000;
    private static final int COLORONCOLOR = 3;

    private final WinDef.HDC screen;
    private final WinDef.HDC memory;
    private final WinDef.HBITMAP bitmap;
    private final WinNT.HANDLE previous;
    private final Pointer bits;
    private final int width;
    private final int height;

    /** Creates a grabber that produces {@code width} x {@code height} frames. */
    ScreenGrabber(int width, int height) {
        this.width = width;
        this.height = height;
        screen = User32.INSTANCE.GetDC(null);
        memory = GDI32.INSTANCE.CreateCompatibleDC(screen);
        WinGDI.BITMAPINFO info = new WinGDI.BITMAPINFO();
        info.bmiHeader.biWidth = width;
        info.bmiHeader.biHeight = -height; // top-down rows
        info.bmiHeader.biPlanes = 1;
        info.bmiHeader.biBitCount = 32;
        info.bmiHeader.biCompression = WinGDI.BI_RGB;
        PointerByReference pixels = new PointerByReference();
        bitmap = GDI32.INSTANCE.CreateDIBSection(screen, info, WinGDI.DIB_RGB_COLORS, pixels, null, 0);
        if (bitmap == null || pixels.getValue() == null) {
            close();
            throw new IllegalStateException("Could not create a capture bitmap.");
        }
        bits = pixels.getValue();
        previous = GDI32.INSTANCE.SelectObject(memory, bitmap);
        Gdi.INSTANCE.SetStretchBltMode(memory, COLORONCOLOR);
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** The region in physical screen pixels, scaled to this grabber's size. Null if Windows refused the copy. */
    BufferedImage grab(Rectangle region) {
        if (!Gdi.INSTANCE.StretchBlt(memory, 0, 0, width, height, screen, region.x, region.y, region.width,
                region.height, SRCCOPY | CAPTUREBLT)) {
            return null;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] target = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        bits.read(0, target, 0, target.length);
        return image;
    }

    @Override
    public void close() {
        if (previous != null) GDI32.INSTANCE.SelectObject(memory, previous);
        if (bitmap != null) GDI32.INSTANCE.DeleteObject(bitmap);
        if (memory != null) GDI32.INSTANCE.DeleteDC(memory);
        if (screen != null) User32.INSTANCE.ReleaseDC(null, screen);
    }
}
