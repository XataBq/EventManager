package ru.eventpass.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;

/** Test APK only. Deliberately lies about MIME to exercise content validation. */
public class CoverFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File source(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!"valid".equals(name) && !"rotated".equals(name) && !"invalid".equals(name) && !"large".equals(name)) throw new FileNotFoundException();
        return new File(getContext().getCacheDir(), "cover-test-" + name);
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException();
        File file = source(uri);
        try {
            if (!file.exists()) {
                if ("valid".equals(uri.getLastPathSegment()) || "rotated".equals(uri.getLastPathSegment())) {
                    Bitmap bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(0xff7766cc);
                    boolean rotated = "rotated".equals(uri.getLastPathSegment());
                    try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(rotated ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG, 100, out); }
                    bitmap.recycle();
                    if (rotated) {
                        android.media.ExifInterface exif = new android.media.ExifInterface(file.getPath());
                        exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION, "6"); exif.saveAttributes();
                    }
                } else if ("large".equals(uri.getLastPathSegment())) {
                    try (RandomAccessFile out = new RandomAccessFile(file, "rw")) { out.setLength(5_242_881); }
                } else try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[] {1, 2, 3, 4}); }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }
    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] args) {
        try { return source(uri).delete() ? 1 : 0; } catch (FileNotFoundException e) { return 0; }
    }
}
