package com.tableos.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class KeystoneProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.tableos.app.keystone"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/config")
        private const val MATCH_CONFIG = 1
    }

    private lateinit var uriMatcher: UriMatcher

    override fun onCreate(): Boolean {
        uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "config", MATCH_CONFIG)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return when (uriMatcher.match(uri)) {
            MATCH_CONFIG -> {
                val prefs = context!!.getSharedPreferences("keystone_prefs", 0)
                val value = prefs.getString("csv", null)
                val cursor = MatrixCursor(arrayOf("value"))
                if (value != null) cursor.addRow(arrayOf(value))
                cursor
            }
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return when (uriMatcher.match(uri)) {
            MATCH_CONFIG -> {
                val v = values?.getAsString("value")
                val prefs = context!!.getSharedPreferences("keystone_prefs", 0)
                prefs.edit().putString("csv", v).apply()
                context?.contentResolver?.notifyChange(CONTENT_URI, null)
                CONTENT_URI
            }
            else -> null
        }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        return when (uriMatcher.match(uri)) {
            MATCH_CONFIG -> {
                val v = values?.getAsString("value")
                val prefs = context!!.getSharedPreferences("keystone_prefs", 0)
                prefs.edit().putString("csv", v).apply()
                context?.contentResolver?.notifyChange(CONTENT_URI, null)
                1
            }
            else -> 0
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return when (uriMatcher.match(uri)) {
            MATCH_CONFIG -> {
                val prefs = context!!.getSharedPreferences("keystone_prefs", 0)
                prefs.edit().remove("csv").apply()
                context?.contentResolver?.notifyChange(CONTENT_URI, null)
                1
            }
            else -> 0
        }
    }

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            MATCH_CONFIG -> "vnd.android.cursor.item/vnd.tableos.keystone"
            else -> null
        }
    }
}