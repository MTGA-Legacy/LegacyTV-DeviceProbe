package com.mtgalegacy.deviceprobe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/** Minimal read-only content provider for handing staged APKs to the system installer. */
public final class ApkContentProvider extends ContentProvider {
    private File uploadDirectory;

    public static Uri uriForFile(android.content.Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".fileprovider")
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        uploadDirectory = new File(getContext().getFilesDir(), "uploads");
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("read-only provider");
        }
        File file = resolve(uri);
        if (file == null || !file.isFile()) {
            throw new FileNotFoundException(uri.toString());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        MatrixCursor cursor = new MatrixCursor(new String[]{"_display_name", "_size"});
        if (file != null && file.isFile()) {
            cursor.addRow(new Object[]{file.getName(), file.length()});
        }
        return cursor;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    private File resolve(Uri uri) {
        if (uploadDirectory == null || uri == null) {
            return null;
        }
        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() != 1) {
            return null;
        }
        File file = new File(uploadDirectory, segments.get(0));
        try {
            String root = uploadDirectory.getCanonicalPath() + File.separator;
            return file.getCanonicalPath().startsWith(root) ? file : null;
        } catch (Exception error) {
            return null;
        }
    }
}
