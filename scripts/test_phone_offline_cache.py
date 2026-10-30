#!/usr/bin/env python3
"""Verify an installed AgentShell cache read-only through same-signed instrumentation.

The temporary instrumentation APK has no activities and replaces Application
startup with a plain Application, so inspection does not connect to the server.
Only counts and inventory hashes are returned unless --backup is requested.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

PACKAGE = "com.agentshell.cachecheck"
JAVA = r'''
package com.agentshell.cachecheck;
import android.app.*;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

public class CacheCheck extends Instrumentation {
  private Bundle options;
  @Override public Application newApplication(ClassLoader loader,String name,Context context)
      throws InstantiationException,IllegalAccessException,ClassNotFoundException {
    return super.newApplication(loader,"android.app.Application",context);
  }
  @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); options=arguments; start(); }
  private long scalar(SQLiteDatabase db,String sql) {
    try(Cursor cursor=db.rawQuery(sql,null)){cursor.moveToFirst();return cursor.getLong(0);}
  }
  private String hex(byte[] bytes) {
    StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format("%02x",b&255));return result.toString();
  }
  @Override public void onStart() {
    Bundle result=new Bundle();
    try {
      Context context=getTargetContext();File path=context.getDatabasePath("agentshell_db");
      JSONObject report=new JSONObject();
      try(SQLiteDatabase db=SQLiteDatabase.openDatabase(path.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY)) {
        report.put("database_version",db.getVersion());
        report.put("sessions",scalar(db,"SELECT COUNT(*) FROM cached_sessions"));
        report.put("conversations",scalar(db,"SELECT COUNT(*) FROM cached_conversations"));
        report.put("messages",scalar(db,"SELECT COUNT(*) FROM cached_chat_messages"));
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(Cursor rows=db.rawQuery("SELECT targetKey,messageId FROM cached_chat_messages ORDER BY targetKey,messageId",null)) {
          while(rows.moveToNext())for(int column=0;column<2;column++){
            digest.update(rows.getString(column).getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
          }
        }
        report.put("inventory_sha256",hex(digest.digest()));
        if(scalar(db,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='cached_history_sync'")>0) {
          report.put("synced_conversations",scalar(db,"SELECT COUNT(*) FROM cached_history_sync WHERE complete=1"));
          report.put("partial_snapshots",scalar(db,"SELECT COUNT(*) FROM cached_history_sync WHERE complete=0"));
        }
      }
      if(options!=null&&"1".equals(options.getString("backup"))) {
        File directory=new File(context.getExternalFilesDir(null),"cache-migration-backup");directory.mkdirs();
        for(String suffix:new String[]{"","-wal"}) {
          File source=new File(path.getAbsolutePath()+suffix);
          if(!source.isFile())continue;
          try(FileInputStream input=new FileInputStream(source);FileOutputStream output=new FileOutputStream(new File(directory,"agentshell_db"+suffix))) {
            byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
          }
        }
        report.put("backup_directory",directory.getAbsolutePath());
      }
      long attachments=0,bytes=0;
      File[] files=new File(context.getFilesDir(),"offline_chat_files").listFiles();
      if(files!=null)for(File file:files)if(file.isFile()&&!file.getName().endsWith(".part")){attachments++;bytes+=file.length();}
      report.put("attachments",attachments);report.put("attachment_bytes",bytes);
      result.putString("cache_report",report.toString());finish(Activity.RESULT_OK,result);
    }catch(Exception error){result.putString("cache_error",error.toString());finish(Activity.RESULT_CANCELED,result);}
  }
}
'''


def run(*command: str, **kwargs) -> str:
    process = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **kwargs)
    if process.returncode:
        raise RuntimeError(process.stdout)
    return process.stdout


def build(output: Path, sdk: Path) -> None:
    tools = sdk / "build-tools/36.0.0"
    android = sdk / "platforms/android-35/android.jar"
    with tempfile.TemporaryDirectory(prefix="agentshell-cache-check-") as temporary:
        directory = Path(temporary)
        source = directory / "CacheCheck.java"
        source.write_text(JAVA)
        classes = directory / "classes"
        classes.mkdir()
        run("javac", "-source", "8", "-target", "8", "-cp", str(android), "-d", str(classes), str(source))
        dex = directory / "dex"
        dex.mkdir()
        run(str(tools / "d8"), "--lib", str(android), "--min-api", "26", "--output", str(dex), *map(str, classes.rglob("*.class")))
        manifest = directory / "AndroidManifest.xml"
        manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{PACKAGE}">
          <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/>
          <application android:label="AgentShell cache check" android:hasCode="true"/>
          <instrumentation android:name=".CacheCheck" android:targetPackage="com.agentshell"/>
        </manifest>''')
        unsigned = directory / "unsigned.apk"
        run(str(tools / "aapt2"), "link", "-I", str(android), "--manifest", str(manifest), "-o", str(unsigned))
        with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.write(dex / "classes.dex", "classes.dex")
        aligned = directory / "aligned.apk"
        run(str(tools / "zipalign"), "-f", "4", str(unsigned), str(aligned))
        output.parent.mkdir(parents=True, exist_ok=True)
        run(str(tools / "apksigner"), "sign", "--ks", str(Path.home() / ".android/debug.keystore"),
            "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", str(output), str(aligned))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Previously verified phone ADB serial")
    parser.add_argument("--build-only", action="store_true")
    parser.add_argument("--apk", type=Path, default=Path(".codex/validation/offline-cache-check.apk"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--backup", type=Path, help="Back up the quiescent database/WAL locally before migration")
    parser.add_argument("--expected-version", type=int)
    parser.add_argument("--sdk", type=Path, default=Path.home() / "android-sdk")
    args = parser.parse_args()
    stamp = args.apk.with_suffix(".source.sha256")
    source_hash = hashlib.sha256(JAVA.encode()).hexdigest()
    if not args.apk.exists() or not stamp.exists() or stamp.read_text().strip() != source_hash:
        build(args.apk, args.sdk)
        stamp.write_text(source_hash + "\n")
    if args.build_only:
        print(args.apk.resolve())
        return
    if not args.serial:
        parser.error("--serial must identify an already verified phone")
    try:
        run("adb", "-s", args.serial, "install", "-r", str(args.apk.resolve()))
        options = ["-e", "backup", "1"] if args.backup else []
        output = run("adb", "-s", args.serial, "shell", "am", "instrument", "-w", *options, f"{PACKAGE}/.CacheCheck", timeout=300)
        match = re.search(r"INSTRUMENTATION_RESULT: cache_report=(.*)", output)
        if not match:
            raise RuntimeError(output)
        report = json.loads(match[1])
        if args.expected_version is not None:
            assert report["database_version"] == args.expected_version, report
        if args.backup:
            args.backup.mkdir(parents=True, exist_ok=True)
            args.backup.chmod(0o700)
            remote = report.pop("backup_directory")
            run("adb", "-s", args.serial, "pull", remote + "/.", str(args.backup.resolve()))
            for name in ("agentshell_db", "agentshell_db-wal"):
                local = args.backup / name
                if local.exists(): local.chmod(0o600)
                run("adb", "-s", args.serial, "shell", "rm", "-f", remote + "/" + name)
            run("adb", "-s", args.serial, "shell", "rmdir", remote)
        print(json.dumps(report, indent=2))
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, indent=2) + "\n")
    finally:
        run("adb", "-s", args.serial, "uninstall", PACKAGE)


if __name__ == "__main__":
    main()
