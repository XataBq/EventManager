package ru.eventpass.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;

/** Test APK only. Java provider runs without the target APK's Kotlin runtime. */
public class DocumentFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return uri.toString().endsWith(".pdf") ? "application/pdf" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name=uri.getLastPathSegment();
        if(!Arrays.asList("ticket.pdf","attendees.xlsx","empty.xlsx","cancelled.pdf","flow.xlsx").contains(name)) throw new FileNotFoundException("Fixture");
        File folder=new File(getContext().getCacheDir(),"t09-samples"); folder.mkdirs();
        return ParcelFileDescriptor.open(new File(folder,name),ParcelFileDescriptor.parseMode(mode));
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder) { return null; }
    @Override public Uri insert(Uri uri,ContentValues values) { return null; }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs) { return 0; }
    @Override public int delete(Uri uri,String selection,String[] selectionArgs) { return 0; }
}
