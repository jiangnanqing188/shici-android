package com.shici.app.core.b4.host;
import java.util.List;
/** Test-only Android Cursor adapter. Java platform String faithfully permits SQL NULL;
 * the old Kotlin !! incorrectly rejected the new real LEFT JOIN before production handled NULL. */
public final class Cursor implements AutoCloseable {
 public final List<String> names; public final List<List<String>> rows; public int index=-1;
 public Cursor(List<String> names,List<List<String>> rows){this.names=names;this.rows=rows;}
 public boolean moveToFirst(){index=0;return !rows.isEmpty();}
 public boolean moveToNext(){index++;return index<rows.size();}
 public int getColumnIndexOrThrow(String s){int i=names.indexOf(s);if(i<0)throw new IllegalArgumentException(s);return i;}
 public String getString(int i){return rows.get(index).get(i);}
 public int getInt(int i){return Integer.parseInt(getString(i));}
 public long getLong(int i){return Long.parseLong(getString(i));}
 public void close(){}
}
