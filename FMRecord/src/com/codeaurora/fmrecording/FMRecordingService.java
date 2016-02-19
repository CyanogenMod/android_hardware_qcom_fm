/*
 * Copyright (c) 2013-2014, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *        * Redistributions of source code must retain the above copyright
 *            notice, this list of conditions and the following disclaimer.
 *        * Redistributions in binary form must reproduce the above copyright
 *            notice, this list of conditions and the following disclaimer in the
 *            documentation and/or other materials provided with the distribution.
 *        * Neither the name of The Linux Foundation nor
 *            the names of its contributors may be used to endorse or promote
 *            products derived from this software without specific prior written
 *            permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.    IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.codeaurora.fmrecording;

import java.util.*;
import android.app.Service;
import java.io.IOException;
import java.lang.ref.WeakReference;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.AudioSystem;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import java.util.Date;
import java.text.SimpleDateFormat;
import android.provider.MediaStore;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import java.io.File;
import android.widget.Toast;
import android.os.UserHandle;
import android.net.Uri;
import android.content.res.Resources;
import android.os.StatFs;
import android.app.Notification;
import android.app.NotificationManager;
import android.widget.RemoteViews;
import android.R.layout;
import android.R.drawable;
import android.content.ComponentName;
import android.content.res.Resources;
import android.os.Process;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;

public class FMRecordingService extends Service {
    private static final String TAG     = "FMRecordingService";
    private BroadcastReceiver mFmRecordingReceiver = null;
    private BroadcastReceiver mFmShutdownReceiver = null;
    public static final long UNAVAILABLE = -1L;
    public static final long PREPARING = -2L;
    public static final long UNKNOWN_SIZE = -3L;
    public static final long LOW_STORAGE_THRESHOLD = 50000000;
    private long mStorageSpace;
    private boolean mFmRecordingOn = false;
    public static final String ACTION_FM_RECORDING =
                       "codeaurora.intent.action.FM_Recording";
    public static final String ACTION_FM_RECORDING_STATUS =
                "codeaurora.intent.action.FM.Recording.Status";

    private File mSampleFile = null;
    private MediaRecorder mRecorder = null;
    private long mSampleStart = 0;
    static final int START = 1;
    static final int STOP = 0;
    private int mRecordDuration = -1;
    private Thread mStatusCheckThread = null;
    private int clientPid = -1;
    private String clientProcessName = "";
    private String mAudioType = "audio/*";
    private long startTimerMs = 0;
    private long stopTimerMs = 0;

    public void onCreate() {

        super.onCreate();
        Log.d(TAG, "FMRecording Service onCreate");
        registerRecordingListner();
        registerShutdownListner();
    }

    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.d(TAG, "FMRecording Service onCreate");
        return START_NOT_STICKY;
    }

    public void onDestroy() {
        Log.d(TAG, "FMRecording Service onDestroy");
        if (mFmRecordingOn == true) {
            Log.d(TAG, "Still recording on progress, Stoping it");
            stopRecord();
        }
        unregisterBroadCastReceiver(mFmRecordingReceiver);
        unregisterBroadCastReceiver(mFmShutdownReceiver);
        super.onDestroy();
    }

    public IBinder onBind(Intent intent) {
        Log.v(TAG, "FMRecording Service onBind");
        return null;
    }

    private void unregisterBroadCastReceiver(BroadcastReceiver myreceiver) {

       if (myreceiver != null) {
           unregisterReceiver(myreceiver);
           myreceiver = null;
       }
    }

    private void registerShutdownListner() {
        if (mFmShutdownReceiver == null) {
            mFmShutdownReceiver = new BroadcastReceiver() {
                 @Override
                 public void onReceive(Context context, Intent intent) {
                     Log.d(TAG, "Received intent " +intent);
                     String action = intent.getAction();
                     Log.d(TAG, " action = " +action);
                     if (action.equals("android.intent.action.ACTION_SHUTDOWN")) {
                         Log.d(TAG, "android.intent.action.ACTION_SHUTDOWN Intent received");
                         stopRecord();
                     }
                 }
            };
            IntentFilter iFilter = new IntentFilter();
            iFilter.addAction("android.intent.action.ACTION_SHUTDOWN");
            registerReceiver(mFmShutdownReceiver, iFilter);
        }
    }

    private static long getAvailableSpace() {
        String state = Environment.getExternalStorageState();
        Log.d(TAG, "External storage state=" + state);
        if (Environment.MEDIA_CHECKING.equals(state)) {
            return PREPARING;
        }
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            return UNAVAILABLE;
        }

        try {
             File sampleDir = Environment.getExternalStorageDirectory();
             StatFs stat = new StatFs(sampleDir.getAbsolutePath());
             return stat.getAvailableBlocks() * (long) stat.getBlockSize();
        } catch (Exception e) {
             Log.i(TAG, "Fail to access external storage", e);
        }
        return UNKNOWN_SIZE;
    }

    private boolean updateAndShowStorageHint() {
        mStorageSpace = getAvailableSpace();
        return showStorageHint();
    }

    private boolean showStorageHint() {
        String errorMessage = null;
        if (mStorageSpace == UNAVAILABLE) {
            errorMessage = getString(R.string.no_storage);
        } else if (mStorageSpace == PREPARING) {
            errorMessage = getString(R.string.preparing_sd);
        } else if (mStorageSpace == UNKNOWN_SIZE) {
            errorMessage = getString(R.string.access_sd_fail);
        } else if (mStorageSpace < LOW_STORAGE_THRESHOLD) {
            errorMessage = getString(R.string.spaceIsLow_content);
        }

        if (errorMessage != null) {
            Toast.makeText(this, errorMessage,
                         Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void sendRecordingStatusIntent(int status) {
        Intent intent = new Intent(ACTION_FM_RECORDING_STATUS);
        intent.putExtra("state", status);
        Log.d(TAG, "posting intent for FM Recording status as = " +status);
        getApplicationContext().sendBroadcastAsUser(intent, UserHandle.ALL);
    }
    private boolean startRecord() {

        Log.d(TAG, "Enter startRecord");
        if (mRecorder != null) { /* Stop existing recording if any */
            Log.d(TAG, "Stopping existing record");
            try {
                 mRecorder.stop();
                 mRecorder.reset();
                 mRecorder.release();
                 mRecorder = null;
            } catch(Exception e) {
                 e.printStackTrace();
            }
        }
        if (!updateAndShowStorageHint())
            return false;
        long maxFileSize = mStorageSpace - LOW_STORAGE_THRESHOLD;
        mRecorder = new MediaRecorder();
        try {
             mRecorder.setMaxFileSize(maxFileSize);
             if(mRecordDuration >= 0)
                mRecorder.setMaxDuration(mRecordDuration);
        } catch (RuntimeException exception) {

        }
        mSampleFile = null;
        File sampleDir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() +"/FMRecording");

        if (getResources().getBoolean(R.bool.def_save_name_prefix_enabled)) {
            String fmRecordSavePath = getApplicationContext().getResources()
                    .getString(R.string.def_fmRecord_savePath);
            sampleDir = new File(Environment.getExternalStorageDirectory().toString()
                    + fmRecordSavePath);
            if (!sampleDir.exists()) {
                sampleDir.mkdirs();
            }
        }
        if(!(sampleDir.mkdirs() || sampleDir.isDirectory()))
            return false;

        try {
             mSampleFile = File.createTempFile("FMRecording", ".aac", sampleDir);
            if (getResources().getBoolean(R.bool.def_save_name_format_enabled)) {
                String suffix = getResources().getString(R.string.def_save_name_suffix);
                suffix = "".equals(suffix) ? ".aac" : suffix;
                mSampleFile = createTempFile("FMRecording", suffix, sampleDir);
            }
        } catch (IOException e) {
             Log.e(TAG, "Not able to access SD Card");
             Toast.makeText(this, "Not able to access SD Card", Toast.LENGTH_SHORT).show();
        }
        try {
             Log.d(TAG, "AudioSource.FM_RX" +MediaRecorder.AudioSource.FM_RX);
             mRecorder.setAudioSource(MediaRecorder.AudioSource.FM_RX);
             mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
             mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
             mAudioType = "audio/aac";
        } catch (RuntimeException exception) {
             Log.d(TAG, "RuntimeException while settings");
             mRecorder.reset();
             mRecorder.release();
             mRecorder = null;
             return false;
        }
        Log.d(TAG, "setOutputFile");
        mRecorder.setOutputFile(mSampleFile.getAbsolutePath());
        try {
             mRecorder.prepare();
             Log.d(TAG, "start");
             mRecorder.start();
             startTimerMs = System.currentTimeMillis();
        } catch (IOException e) {
             Log.d(TAG, "IOException while start");
             mRecorder.reset();
             mRecorder.release();
             mRecorder = null;
             return false;
        } catch (RuntimeException e) {
             Log.d(TAG, "RuntimeException while start");
             mRecorder.reset();
             mRecorder.release();
             mRecorder = null;
             return false;
        }
        mFmRecordingOn = true;
        Log.d(TAG, "mSampleFile.getAbsolutePath() " +mSampleFile.getAbsolutePath());
        mRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
             public void onInfo(MediaRecorder mr, int what, int extra) {
                 if ((what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) ||
                     (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)) {
                     if (mFmRecordingOn) {
                         Log.d(TAG, "Maximum file size/duration reached, stopping the recording");
                         stopRecord();
                     }
                     if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                         // Show the toast.
                         Toast.makeText(FMRecordingService.this,
                                        R.string.FMRecording_reach_size_limit,
                                        Toast.LENGTH_LONG).show();
                     }
                 }
             }
             // from MediaRecorder.OnErrorListener
             public void onError(MediaRecorder mr, int what, int extra) {
                 Log.e(TAG, "MediaRecorder error. what=" + what + ". extra=" + extra);
                 if (what == MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN) {
                     // We may have run out of space on the sdcard.
                     if (mFmRecordingOn) {
                         stopRecord();
                     }
                     updateAndShowStorageHint();
                 }
             }
        });
        mSampleStart = System.currentTimeMillis();
        sendRecordingStatusIntent(START);
        startNotification();
        return true;
    }

    private void startNotification() {
        RemoteViews views = new RemoteViews(getPackageName(), R.layout.record_status_bar);
        Notification status = new Notification();
        status.contentView = views;
        status.flags |= Notification.FLAG_ONGOING_EVENT;
        status.icon = R.drawable.ic_menu_record;
        startForeground(102, status);
    }

    private void stopRecord() {
        Log.d(TAG, "Enter stopRecord");
        mFmRecordingOn = false;
        if (mRecorder == null)
            return;
        try {
             mRecorder.stop();
             stopTimerMs = System.currentTimeMillis();
             mRecorder.reset();
             mRecorder.release();
             mRecorder = null;
        } catch(Exception e) {
             e.printStackTrace();
        }

        sendRecordingStatusIntent(STOP);
        saveFile();
        stopForeground(true);
        stopClientStatusCheck();
    }

    private void saveFile() {
        int sampleLength = (int)((System.currentTimeMillis() - mSampleStart)/1000 );
        Log.d(TAG, "Enter saveFile");
        if (sampleLength == 0)
            return;
        String state = Environment.getExternalStorageState();
        Log.d(TAG, "storage state is " + state);

        if (Environment.MEDIA_MOUNTED.equals(state)) {
            try {
                 this.addToMediaDB(mSampleFile);
                 Toast.makeText(this,getString(R.string.save_record_file,
                                               mSampleFile.getAbsolutePath( )),
                                               Toast.LENGTH_LONG).show();
            } catch(Exception e) {
                 e.printStackTrace();
            }
        } else {
            Log.e(TAG, "SD card must have removed during recording. ");
            Toast.makeText(this, "Recording aborted", Toast.LENGTH_SHORT).show();
        }
        return;
    }

    /*
     * Adds file and returns content uri.
     */
    private Uri addToMediaDB(File file) {
        Log.d(TAG, "In addToMediaDB");
        Resources res = getResources();
        ContentValues cv = new ContentValues();
        int audioId = -1;
        long current = System.currentTimeMillis();
        long modDate = file.lastModified();
        long recordDuration = stopTimerMs - startTimerMs;
        Date date = new Date(current);
        SimpleDateFormat formatter = new SimpleDateFormat(
                  res.getString(R.string.audio_db_title_format));
        String title = formatter.format(date);

        // Lets label the recorded audio file as NON-MUSIC so that the file
        // won't be displayed automatically, except for in the playlist.
        cv.put(MediaStore.Audio.Media.IS_MUSIC, "1");
        cv.put(MediaStore.Audio.Media.TITLE, title);
        cv.put(MediaStore.Audio.Media.DATA, file.getAbsolutePath());
        cv.put(MediaStore.Audio.Media.DATE_ADDED, (int) (current / 1000));
        cv.put(MediaStore.Audio.Media.DATE_MODIFIED, (int) (modDate / 1000));
        cv.put(MediaStore.Audio.Media.DURATION, recordDuration);
        cv.put(MediaStore.Audio.Media.MIME_TYPE, mAudioType);
        cv.put(MediaStore.Audio.Media.ARTIST,
                res.getString(R.string.audio_db_artist_name));
        cv.put(MediaStore.Audio.Media.ALBUM,
                res.getString(R.string.audio_db_album_name));
        audioId = getFileIdInVideoDB(file);
        if (audioId != -1) {//remove the record if it is already added into video table.
            removeRecordInVideoDB(audioId);
            Log.d(TAG, "Remove audio record " + audioId + " in video database");
        }
        Log.d(TAG, "Inserting audio record: " + cv.toString());
        ContentResolver resolver = getContentResolver();
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        Log.d(TAG, "ContentURI: " + base);
        Uri result = resolver.insert(base, cv);
        if (result == null) {
            Toast.makeText(this, R.string.unable_to_store, Toast.LENGTH_SHORT).show();
            return null;
        }
        if (getPlaylistId(res) == -1) {
            createPlaylist(res, resolver);
        }
        audioId = Integer.valueOf(result.getLastPathSegment());
        addToPlaylist(resolver, audioId, getPlaylistId(res));

        // Notify those applications such as Music listening to the
        // scanner events that a recorded audio file just created.
        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, result));
        return result;
    }

    private int getFileIdInVideoDB(File file) {
        int id = -1;
        final String where = MediaStore.Video.Media.DATA + "=?";
        final String[] args = new String[] { file.getAbsolutePath() };
        final String[] ids = new String[] { MediaStore.Video.Media._ID };
        Uri base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

        Cursor cursor = query(base, ids, where, args, null);
        if (cursor == null) {
            Log.v(TAG, "Data query returns null");
        } else {
            if (cursor.moveToNext() != false) {
                id = cursor.getInt(0);
                cursor.close();
            }
        }
        return id;
    }

    private void removeRecordInVideoDB(int id) {
        final String where = MediaStore.Video.Media._ID + "=" + id;
        Uri base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        ContentValues cv = new ContentValues();
        ContentResolver resolver = getContentResolver();

        cv.put(MediaStore.Video.Media.DATA, "null");
        resolver.update(base, cv, where, null);
        resolver.delete(base, where, null);
    }

    private int getPlaylistId(Resources res) {
        Uri uri = MediaStore.Audio.Playlists.getContentUri("external");
        final String[] ids = new String[] { MediaStore.Audio.Playlists._ID };
        final String where = MediaStore.Audio.Playlists.NAME + "=?";
        final String[] args = new String[] { res.getString(R.string.audio_db_playlist_name) };
        Cursor cursor = query(uri, ids, where, args, null);
        if (cursor == null) {
            Log.v(TAG, "query returns null");
        }
        int id = -1;
        if (cursor != null) {
            cursor.moveToFirst();
            if (!cursor.isAfterLast()) {
                id = cursor.getInt(0);
            }
            cursor.close();
        }
        return id;
    }

    private Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
             ContentResolver resolver = getContentResolver();
             if (resolver == null) {
                 return null;
             }
             return resolver.query(uri, projection, selection, selectionArgs, sortOrder);
        } catch (UnsupportedOperationException ex) {
             return null;
        }
    }

    private Uri createPlaylist(Resources res, ContentResolver resolver) {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Audio.Playlists.NAME, res.getString(R.string.audio_db_playlist_name));
        Uri uri = resolver.insert(MediaStore.Audio.Playlists.getContentUri("external"), cv);
        if (uri == null) {
            Toast.makeText(this, R.string.unable_to_store, Toast.LENGTH_SHORT).show();
        }
        return uri;
    }

    private void addToPlaylist(ContentResolver resolver, int audioId, long playlistId) {
        String[] cols = new String[] {
               "count(*)"
        };
        Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId);
        Cursor cur = resolver.query(uri, cols, null, null, null);
        final int base;
        if (cur != null) {
            cur.moveToFirst();
            base = cur.getInt(0);
            cur.close();
        } else {
            base = 0;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, Integer.valueOf(base + audioId));
        values.put(MediaStore.Audio.Playlists.Members.AUDIO_ID, audioId);
        resolver.insert(uri, values);
    }

    private void registerRecordingListner() {
        if (mFmRecordingReceiver == null) {
            mFmRecordingReceiver = new BroadcastReceiver() {
                 @Override
                 public void onReceive(Context context, Intent intent) {
                     Log.d(TAG, "Received intent " +intent);
                     String action = intent.getAction();
                     Log.d(TAG, " action = " +action);
                     if (action.equals(ACTION_FM_RECORDING)) {
                         int state = intent.getIntExtra("state", STOP);
                         Log.d(TAG, "ACTION_FM_RECORDING Intent received" + state);
                         if (state == START) {
                             Log.d(TAG, "Recording start");
                             mRecordDuration = intent.getIntExtra("record_duration", mRecordDuration);
                             if(startRecord()) {
                                clientProcessName = intent.getStringExtra("process_name");
                                clientPid = intent.getIntExtra("process_id", -1);
                                startClientStatusCheck();
                             }
                         } else if (state == STOP) {
                             Log.d(TAG, "Stop recording");
                             stopRecord();
                         }
                     }
                 }
            };
            IntentFilter iFilter = new IntentFilter();
            iFilter.addAction(ACTION_FM_RECORDING);
            registerReceiver(mFmRecordingReceiver, iFilter);
        }
    }

    private boolean getClientStatus(int pid, String processName) {
      boolean status = false;
      ActivityManager actvityManager =
                    (ActivityManager)this.getSystemService(
                                                           this.ACTIVITY_SERVICE);

      List<RunningAppProcessInfo> procInfos =
                                     actvityManager.getRunningAppProcesses();

      for(RunningAppProcessInfo procInfo : procInfos) {
         if ((pid == procInfo.pid)
               &&
              (procInfo.processName.equals(processName))) {
              status = true;
              break;
         }
      }
      procInfos.clear();
      return status;
    }

    private Runnable clientStatusCheckThread = new Runnable() {
       @Override
       public void run() {
          while(!Thread.currentThread().isInterrupted()) {
                try {
                     if(!getClientStatus(clientPid, clientProcessName)) {
                        stopRecord();
                        break;
                     };
                     Thread.sleep(500);
                }catch(Exception e) {
                     Log.d(TAG, "Client status check thread interrupted");
                     break;
                }
          }
       }
    };

   private void startClientStatusCheck() {
       if((mStatusCheckThread == null) ||
          (mStatusCheckThread.getState() == Thread.State.TERMINATED)) {
           mStatusCheckThread = new Thread(null,
                                           clientStatusCheckThread,
                                           "GetClientStatus");
       }
       if((mStatusCheckThread != null) &&
          (mStatusCheckThread.getState() == Thread.State.NEW)) {
           mStatusCheckThread.start();
       }
   }

   private void stopClientStatusCheck() {
       if(mStatusCheckThread != null) {
          mStatusCheckThread.interrupt();
       }
   }

    public File createTempFile(String prefix, String suffix, File directory)
            throws IOException {
        prefix = getResources().getString(R.string.def_save_name_prefix) + '-';
        // Force a prefix null check first
        if (prefix.length() < 3) {
            throw new IllegalArgumentException("prefix must be at least 3 characters");
        }
        if (suffix == null) {
            suffix = ".tmp";
        }
        File tmpDirFile = directory;
        if (tmpDirFile == null) {
            String tmpDir = System.getProperty("java.io.tmpdir", ".");
            tmpDirFile = new File(tmpDir);
        }

        String nameFormat = getResources().getString(R.string.def_save_name_format);
        SimpleDateFormat df = new SimpleDateFormat(nameFormat);
        String currentTime = df.format(System.currentTimeMillis());

        File result;
        do {
            result = new File(tmpDirFile, prefix + currentTime + suffix);
        } while (!result.createNewFile());
        return result;
   }
}
