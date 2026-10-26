package com.vlcsubfont.module;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 向注入端（VLC 进程）暴露配置与字体文件。
 * exported 只读：数据仅为用户自选的字体文件，不含敏感信息。
 */
public class FontProvider extends ContentProvider {

    private static final String[] COLUMNS = {"enabled", "font_name", "version", "font_size"};

    private SharedPreferences mPrefs;
    private File mFontFile;

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        mPrefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE);
        mFontFile = new File(ctx.getFilesDir(), "font.ttf");
        return true;
    }

    private boolean effectiveEnabled() {
        return mPrefs.getBoolean("enabled", false) && mFontFile.canRead();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(COLUMNS);
        c.addRow(new Object[]{
                effectiveEnabled() ? 1 : 0,
                mPrefs.getString("font_name", null),
                mPrefs.getLong("version", 0L),
                mFontFile.canRead() ? mFontFile.length() : 0L,
        });
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"font".equals(uri.getLastPathSegment()) || !mFontFile.canRead()) {
            throw new FileNotFoundException(uri.toString());
        }
        return ParcelFileDescriptor.open(mFontFile, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "font".equals(uri.getLastPathSegment())
                ? "font/ttf"
                : "vnd.android.cursor.item/vnd." + FontStore.AUTHORITY + ".config";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
