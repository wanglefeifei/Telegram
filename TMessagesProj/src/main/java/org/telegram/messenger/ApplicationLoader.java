/*
 * This is the source code of Telegram for Android v. 3.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2017.
 */

package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.firebase.iid.FirebaseInstanceId;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.ForegroundDetector;

import java.io.File;
import java.util.UUID;

import BnetSDK.BNetApplication;
import BnetSDK.SharePreferenceMain;
import Service.BnetService;
import model.BnetServiceJoinParams;
import network.b.bnet.service.BnetAidlInterface;

public class ApplicationLoader extends Application {

    @SuppressLint("StaticFieldLeak")
    public static volatile Context applicationContext;
    public static volatile Handler applicationHandler;
    private static volatile boolean applicationInited = false;

    public static volatile boolean isScreenOn = false;
    public static volatile boolean mainInterfacePaused = true;
    public static volatile boolean externalInterfacePaused = true;
    public static volatile boolean mainInterfacePausedStageQueue = true;
    public static volatile long mainInterfacePausedStageQueueTime;

    public static boolean isChecked = false;
    private static ServiceConnection serviceConnection;
    public static boolean serviceBind = false;
    private BnetAidlInterface bnetAidlInterface;
    private Intent mIntentConnectorService;
    private BnetServiceJoinParams bnetServiceJoinParams;
    private static  ApplicationLoader applicationLoader = null;

    public static ApplicationLoader getInstance() {
        return applicationLoader;
    }

    public void StartVpvJoin() {
        String dWalletAddr = SharePreferenceMain.getSharedPreference(applicationContext.getApplicationContext()).getdWalletAddr();
        if (dWalletAddr == null) {
            dWalletAddr = UUID.randomUUID().toString();
            SharePreferenceMain.getSharedPreference(applicationContext.getApplicationContext()).savedWalletAddr(dWalletAddr);
        }
        BnetServiceJoin(null, dWalletAddr, "", 32);
    }
    public void DestoryBnetService() {
        isChecked = false;
        if (serviceConnection != null && serviceBind) {
            Log.d("wanglf", "DestoryBnetService:   unbindsercice ");
            unbindService(serviceConnection);
            serviceBind = false;
        }
        if (bnetAidlInterface != null) {
            try {
                bnetAidlInterface.leave();
                stopService(mIntentConnectorService);
                bnetAidlInterface = null;
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    private void startAndBindService() {
        Intent bnetService = new Intent(applicationContext, BnetService.class);
        startService(bnetService);


        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                Log.d("wanglf", "onServiceConnected:..... ");
                bnetAidlInterface = BnetAidlInterface.Stub.asInterface(iBinder);
                isChecked = true;
                serviceBind = true;
                StartBnetServiceJoin();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
            }
        };
        mIntentConnectorService = new Intent(applicationContext,BnetService.class);
        bindService(mIntentConnectorService, serviceConnection, BIND_AUTO_CREATE);
    }

    public void BnetServiceJoin(String nWalletAddr, String dWalletAddr, String deviceAddr, int maskBit) {
        bnetServiceJoinParams = new BnetServiceJoinParams(nWalletAddr, dWalletAddr, deviceAddr, maskBit);
        if (bnetAidlInterface == null || !serviceBind) {
            startAndBindService();
        } else {
            StartBnetServiceJoin();
        }
    }

    private void StartBnetServiceJoin() {
        if (bnetServiceJoinParams != null && bnetAidlInterface != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        bnetAidlInterface.join(bnetServiceJoinParams.getnWalletAddr(), bnetServiceJoinParams.getdWalletAddr(), bnetServiceJoinParams.getDeviceAddr(), bnetServiceJoinParams.getMaskBit());
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
            }).start();

            try {
                bnetAidlInterface.CStartService();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }
    public static File getFilesDirFixed() {
        for (int a = 0; a < 10; a++) {
            File path = ApplicationLoader.applicationContext.getFilesDir();
            if (path != null) {
                return path;
            }
        }
        try {
            ApplicationInfo info = applicationContext.getApplicationInfo();
            File path = new File(info.dataDir, "files");
            path.mkdirs();
            return path;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return new File("/data/data/org.telegram.messenger/files");
    }

    public static void postInitApplication() {
        if (applicationInited) {
            return;
        }

        applicationInited = true;

        try {
            LocaleController.getInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            final BroadcastReceiver mReceiver = new ScreenReceiver();
            applicationContext.registerReceiver(mReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            PowerManager pm = (PowerManager)ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            isScreenOn = pm.isScreenOn();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("screen state = " + isScreenOn);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        SharedConfig.loadConfig();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig.getInstance(a).loadConfig();
            MessagesController.getInstance(a);
            ConnectionsManager.getInstance(a);
            TLRPC.User user = UserConfig.getInstance(a).getCurrentUser();
            if (user != null) {
                MessagesController.getInstance(a).putUser(user, true);
                MessagesController.getInstance(a).getBlockedUsers(true);
                SendMessagesHelper.getInstance(a).checkUnsentMessages();
            }
        }

        ApplicationLoader app = (ApplicationLoader)ApplicationLoader.applicationContext;
        app.initPlayServices();
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("app initied");
        }

        MediaController.getInstance();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            ContactsController.getInstance(a).checkAppAccount();
            DownloadController.getInstance(a);
        }

        WearDataLayerListenerService.updateWatchConnectionState();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        applicationLoader = this;
        applicationContext = getApplicationContext();
        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);
        ConnectionsManager.native_setJava(false);
        new ForegroundDetector(this);

        applicationHandler = new Handler(applicationContext.getMainLooper());

        AndroidUtilities.runOnUIThread(ApplicationLoader::startPushService);
    }

    public static void startPushService() {
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.getBoolean("pushService", true)) {
            try {
                applicationContext.startService(new Intent(applicationContext, NotificationsService.class));
            } catch (Throwable ignore) {

            }
        } else {
            stopPushService();
        }
    }

    public static void stopPushService() {
        applicationContext.stopService(new Intent(applicationContext, NotificationsService.class));

        PendingIntent pintent = PendingIntent.getService(applicationContext, 0, new Intent(applicationContext, NotificationsService.class), 0);
        AlarmManager alarm = (AlarmManager)applicationContext.getSystemService(Context.ALARM_SERVICE);
        alarm.cancel(pintent);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            LocaleController.getInstance().onDeviceConfigurationChange(newConfig);
            AndroidUtilities.checkDisplaySize(applicationContext, newConfig);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initPlayServices() {
        AndroidUtilities.runOnUIThread(() -> {
            if (checkPlayServices()) {
                final String currentPushString = SharedConfig.pushString;
                if (!TextUtils.isEmpty(currentPushString)) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("GCM regId = " + currentPushString);
                    }
                } else {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("GCM Registration not found.");
                    }
                }
                Utilities.globalQueue.postRunnable(() -> {
                    try {
                        String token = FirebaseInstanceId.getInstance().getToken();
                        if (!TextUtils.isEmpty(token)) {
                            GcmInstanceIDListenerService.sendRegistrationToServer(token);
                        }
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                });
            } else {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("No valid Google Play Services APK found.");
                }
            }
        }, 1000);
    }

    private boolean checkPlayServices() {
        try {
            int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
            return resultCode == ConnectionResult.SUCCESS;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }
}
