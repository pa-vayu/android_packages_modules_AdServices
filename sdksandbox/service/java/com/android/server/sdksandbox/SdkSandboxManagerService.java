/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.sdksandbox;

import static android.app.sdksandbox.SdkSandboxManager.SDK_SANDBOX_SERVICE;

import android.annotation.RequiresPermission;
import android.app.ActivityManager;
import android.app.sdksandbox.IRemoteSdkCallback;
import android.app.sdksandbox.ISdkSandboxManager;
import android.app.sdksandbox.SdkSandboxManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.view.SurfaceControlViewHost;

import com.android.internal.annotations.GuardedBy;
import com.android.sdksandbox.ISdkSandboxManagerToSdkSandboxCallback;
import com.android.sdksandbox.ISdkSandboxService;
import com.android.sdksandbox.ISdkSandboxToSdkSandboxManagerCallback;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Implementation of {@link SdkSandboxManager}.
 *
 * @hide
 */
public class SdkSandboxManagerService extends ISdkSandboxManager.Stub {

    private static final String TAG = "SdkSandboxManager";

    private final Context mContext;
    private final SdkTokenManager mSdkTokenManager = new SdkTokenManager();
    private final ActivityManager mActivityManager;
    private final Handler mHandler;

    private final SdkSandboxServiceProvider mServiceProvider;

    // For communication between app<-ManagerService->RemoteCode for each codeToken
    // TODO(b/208824602): Remove from this map when an app dies.
    @GuardedBy("mAppAndRemoteSdkLinks")
    private final ArrayMap<IBinder, AppAndRemoteSdkLink> mAppAndRemoteSdkLinks = new ArrayMap<>();

    @GuardedBy("mAppLoadedSdkUids")
    private final ArrayMap<Integer, HashSet<Integer>> mAppLoadedSdkUids = new ArrayMap<>();

    SdkSandboxManagerService(Context context, SdkSandboxServiceProvider provider) {
        mContext = context;
        mServiceProvider = provider;
        mActivityManager = mContext.getSystemService(ActivityManager.class);
        mHandler = new Handler(Looper.getMainLooper());

        final IntentFilter packageIntentFilter = new IntentFilter();
        packageIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageIntentFilter.addDataScheme("package");

        BroadcastReceiver packageIntentReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int sdkUid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                if (sdkUid == -1) {
                    return;
                }
                final boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                if (replacing) {
                    onSdkUpdating(sdkUid);
                }
            }
        };
        mContext.registerReceiver(packageIntentReceiver, packageIntentFilter,
                null /* broadcastPermission */, mHandler);
    }

    private void onSdkUpdating(int sdkUid) {
        final ArrayList<Integer> appUids = new ArrayList<>();
        synchronized (mAppLoadedSdkUids) {
            for (Map.Entry<Integer, HashSet<Integer>> appEntry :
                    mAppLoadedSdkUids.entrySet()) {
                final int appUid = appEntry.getKey();
                final HashSet<Integer> loadedCodeUids = appEntry.getValue();

                if (loadedCodeUids.contains(sdkUid)) {
                    appUids.add(appUid);
                }
            }
        }
        for (Integer appUid : appUids) {
            Log.i(TAG, "Killing app " + appUid + " containing code " + sdkUid);
            mActivityManager.killUid(appUid, "Package updating");
        }
    }

    @Override
    public void loadSdk(String name, Bundle params, IRemoteSdkCallback callback) {
        final int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            loadSdkWithClearIdentity(callingUid, name, params, callback);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void loadSdkWithClearIdentity(int callingUid, String name, Bundle params,
            IRemoteSdkCallback callback) {
        // Step 1: create unique identity for the {callingUid, name} pair
        final IBinder sdkToken = mSdkTokenManager.createOrGetSdkToken(callingUid, name);

        // Ensure we are not already loading sdk for this sdkToken. That's determined by
        // checking if we already have an AppAndRemoteCodeLink for the sdkToken.
        final AppAndRemoteSdkLink link = new AppAndRemoteSdkLink(sdkToken, callback);
        synchronized (mAppAndRemoteSdkLinks) {
            if (mAppAndRemoteSdkLinks.putIfAbsent(sdkToken, link) != null) {
                link.sendLoadSdkErrorToApp(SdkSandboxManager.LOAD_SDK_SDK_ALREADY_LOADED,
                        name + " is being loaded or has been loaded already");
                return;
            }
        }
        // Step 2: fetch the installed code in device
        final ApplicationInfo info = getSdkInfo(name, callingUid);

        if (info == null) {
            String errorMsg = name + " not found for loading";
            Log.w(TAG, errorMsg);
            link.sendLoadSdkErrorToApp(SdkSandboxManager.LOAD_SDK_SDK_NOT_FOUND, errorMsg);
            return;
        }
        // TODO(b/204991850): ensure requested code is included in the AndroidManifest.xml

        invokeSdkSandboxServiceToLoadSdk(callingUid, sdkToken, info, params, link);

        // Register a death recipient to clean up sdkToken and unbind its service after app dies.
        try {
            callback.asBinder().linkToDeath(() -> {
                onAppDeath(sdkToken, callingUid);
            }, 0);
        } catch (RemoteException re) {
            // App has already died, cleanup sdk token and link, and unbind its service
            onAppDeath(sdkToken, callingUid);
        }
    }

    private void onAppDeath(IBinder sdkToken, int appUid) {
        cleanUp(sdkToken);
        final int sdkSandboxUid = Process.toSdkSandboxUid(appUid);
        mServiceProvider.unbindService(appUid);
        synchronized (mAppLoadedSdkUids) {
            mAppLoadedSdkUids.remove(appUid);
        }
        Log.i(TAG, "Killing sdk sandbox process " + sdkSandboxUid);
        mActivityManager.killUid(sdkSandboxUid, "App " + appUid + " has died");
    }

    ApplicationInfo getSdkInfo(String sharedLibraryName, int callingUid) {
        try {
            PackageManager pm = mContext.getPackageManager();
            String[] packageNames = pm.getPackagesForUid(callingUid);
            for (int i = 0; i < packageNames.length; i++) {
                ApplicationInfo info = pm.getApplicationInfo(
                        packageNames[i], PackageManager.GET_SHARED_LIBRARY_FILES);
                List<SharedLibraryInfo> sharedLibraries = info.getSharedLibraryInfos();
                for (int j = 0; j < sharedLibraries.size(); j++) {
                    SharedLibraryInfo sharedLibrary = sharedLibraries.get(j);
                    if (sharedLibrary.getType() != SharedLibraryInfo.TYPE_SDK_PACKAGE) {
                        continue;
                    }

                    if (!sharedLibraryName.equals(sharedLibrary.getName())) {
                        continue;
                    }

                    PackageInfo packageInfo = pm.getPackageInfo(sharedLibrary.getDeclaringPackage(),
                            PackageManager.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES);
                    return packageInfo.applicationInfo;
                }
            }
            return null;
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    @Override
    public void requestSurfacePackage(IBinder sdkToken, IBinder hostToken,
            int displayId, Bundle params) {
        //TODO(b/204991850): verify that sdkToken belongs to the callingUid
        final long token = Binder.clearCallingIdentity();
        try {
            requestSurfacePackageWithClearIdentity(sdkToken,
                    hostToken, displayId, params);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void requestSurfacePackageWithClearIdentity(IBinder sdkToken,
            IBinder hostToken, int displayId, Bundle params) {
        synchronized (mAppAndRemoteSdkLinks) {
            if (!mAppAndRemoteSdkLinks.containsKey(sdkToken)) {
                throw new SecurityException("sdkToken is invalid");
            }
            final AppAndRemoteSdkLink link = mAppAndRemoteSdkLinks.get(sdkToken);
            link.requestSurfacePackageToCode(hostToken, displayId, params);
        }
    }

    @Override
    public void sendData(int id, Bundle params) {
    }

    @Override
    @RequiresPermission(android.Manifest.permission.DUMP)
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mContext.enforceCallingPermission(android.Manifest.permission.DUMP,
                "Can't dump " + TAG);

        // TODO(b/211575098): Use IndentingPrintWriter for better formatting
        synchronized (mAppAndRemoteSdkLinks) {
            writer.println("mAppAndRemoteSdkLinks size: " + mAppAndRemoteSdkLinks.size());
        }

        writer.println("mSdkTokenManager:");
        mSdkTokenManager.dump(writer);
        writer.println();

        writer.println("mServiceProvider:");
        mServiceProvider.dump(writer);
        writer.println();
    }

    private void invokeSdkSandboxServiceToLoadSdk(
            int callingUid, IBinder sdkToken, ApplicationInfo info, Bundle params,
            AppAndRemoteSdkLink link) {

        // check first if service already bound
        ISdkSandboxService service = mServiceProvider.getBoundServiceForApp(callingUid);
        if (service != null) {
            loadSdkForService(callingUid, sdkToken, info, params, link, service);
            return;
        }

        mServiceProvider.bindService(
                callingUid,
                new ServiceConnection() {
                    private boolean mIsServiceBound = false;

                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        final ISdkSandboxService mService =
                                ISdkSandboxService.Stub.asInterface(service);
                        Log.i(TAG, "Sdk sandbox has been bound");
                        mServiceProvider.setBoundServiceForApp(callingUid, mService);

                        // Ensuring the code is not loaded again if connection restarted
                        if (!mIsServiceBound) {
                            loadSdkForService(callingUid, sdkToken, info, params, link, mService);
                            mIsServiceBound = true;
                        }
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        // Sdk sandbox crashed or killed, system will start it again.
                        // TODO(b/204991850): Handle restarts differently
                        //  (e.g. Exponential backoff retry strategy)
                        mServiceProvider.setBoundServiceForApp(callingUid, null);
                    }

                    @Override
                    public void onBindingDied(ComponentName name) {
                        mServiceProvider.setBoundServiceForApp(callingUid, null);
                        mServiceProvider.unbindService(callingUid);
                        mServiceProvider.bindService(callingUid, this);
                    }

                    @Override
                    public void onNullBinding(ComponentName name) {
                        link.sendLoadSdkErrorToApp(
                                SdkSandboxManager.LOAD_SDK_INTERNAL_ERROR,
                                "Failed to bind the service");
                    }
                }
        );
    }

    private void loadSdkForService(
            int callingUid, IBinder sdkToken, ApplicationInfo info, Bundle params,
            AppAndRemoteSdkLink link, ISdkSandboxService service) {
        try {
            // TODO(b/208631926): Pass a meaningful value for codeProviderClassName
            service.loadSdk(sdkToken, info, "", params, link);

            onSdkLoaded(callingUid, info.uid);
        } catch (RemoteException e) {
            String errorMsg = "Failed to load code";
            Log.w(TAG, errorMsg, e);
            link.sendLoadSdkErrorToApp(
                    SdkSandboxManager.LOAD_SDK_INTERNAL_ERROR, errorMsg);
        }
    }

    private void onSdkLoaded(int appUid, int sdkUid) {
        synchronized (mAppLoadedSdkUids) {
            final HashSet<Integer> sdkUids = mAppLoadedSdkUids.get(appUid);
            if (sdkUids != null) {
                sdkUids.add(sdkUid);
            } else {
                mAppLoadedSdkUids.put(appUid, new HashSet<>(Collections.singletonList(sdkUid)));
            }
        }
    }

    /**
     * Clean up all internal data structures related to {@code sdkToken}
     */
    private void cleanUp(IBinder sdkToken) {
        // Destroy the sdkToken first, to free up the {callingUid, name} pair
        mSdkTokenManager.destroy(sdkToken);
        // Now clean up rest of the state which is using an obsolete sdkToken
        synchronized (mAppAndRemoteSdkLinks) {
            mAppAndRemoteSdkLinks.remove(sdkToken);
        }
    }

    @ThreadSafe
    private static class SdkTokenManager {
        // Keep track of codeToken for each unique pair of {callingUid, name}
        @GuardedBy("mSdkTokens")
        final ArrayMap<Pair<Integer, String>, IBinder> mSdkTokens = new ArrayMap<>();
        @GuardedBy("mSdkTokens")
        final ArrayMap<IBinder, Pair<Integer, String>> mReverseSdkTokens = new ArrayMap<>();

        /**
         * For the given {callingUid, name} pair, create unique {@code sdkToken} or
         * return existing one.
         */
        public IBinder createOrGetSdkToken(int callingUid, String name) {
            final Pair<Integer, String> pair = Pair.create(callingUid, name);
            synchronized (mSdkTokens) {
                if (!mSdkTokens.containsKey(pair)) {
                    final IBinder sdkToken = new Binder();
                    mSdkTokens.put(pair, sdkToken);
                    mReverseSdkTokens.put(sdkToken, pair);
                }
                return mSdkTokens.get(pair);
            }
        }

        public void destroy(IBinder sdkToken) {
            synchronized (mSdkTokens) {
                mSdkTokens.remove(mReverseSdkTokens.get(sdkToken));
                mReverseSdkTokens.remove(sdkToken);
            }
        }

        void dump(PrintWriter writer) {
            synchronized (mSdkTokens) {
                if (mSdkTokens.isEmpty()) {
                    writer.println("mSdkTokens is empty");
                } else {
                    writer.print("mSdkTokens size: ");
                    writer.println(mSdkTokens.size());
                    for (Pair<Integer, String> pair : mSdkTokens.keySet()) {
                        writer.printf("callingUid: %s, name: %s", pair.first, pair.second);
                        writer.println();
                    }
                }
            }
        }
    }

    /**
     * A callback object to establish a link between the app calling into manager service
     * and the remote code being loaded in SdkSandbox.
     *
     * Overview of communication:
     * 1. App to ManagerService: App calls into this service via app context
     * 2. ManagerService to App: {@link AppAndRemoteSdkLink} holds reference to
     * {@link IRemoteSdkCallback} object which provides call back into the app.
     * 3. RemoteCode to ManagerService: {@link AppAndRemoteSdkLink} extends
     * {@link ISdkSandboxToSdkSandboxManagerCallback} interface. We
     * pass on this object to {@link ISdkSandboxService} so that remote code
     * can call back into ManagerService
     * 4. ManagerService to RemoteCode: When code is loaded for the first time and remote
     * code calls back with successful result, it also sends reference to
     * {@link ISdkSandboxManagerToSdkSandboxCallback} callback object.
     * ManagerService uses this to callback into the remote code.
     *
     * We maintain a link for each unique {app, remoteCode} pair, which is identified with
     * {@code codeToken}.
     */
    private class AppAndRemoteSdkLink extends
            ISdkSandboxToSdkSandboxManagerCallback.Stub {
        // The codeToken for which this channel has been created
        private final IBinder mSdkToken;
        private final IRemoteSdkCallback mManagerToAppCallback;

        @GuardedBy("this")
        private ISdkSandboxManagerToSdkSandboxCallback mManagerToCodeCallback;

        AppAndRemoteSdkLink(IBinder sdkToken, IRemoteSdkCallback managerToAppCallback) {
            mSdkToken = sdkToken;
            mManagerToAppCallback = managerToAppCallback;
        }

        @Override
        public void onLoadSdkSuccess(
                Bundle params, ISdkSandboxManagerToSdkSandboxCallback callback) {
            // Keep reference to callback so that manager service can
            // callback to remote code loaded.
            synchronized (this) {
                mManagerToCodeCallback = callback;
            }
            sendLoadSdkSuccessToApp(params);
        }

        @Override
        public void onLoadSdkError(int errorCode, String errorMsg) {
            sendLoadSdkErrorToApp(errorCode, errorMsg);
        }

        @Override
        public void onSurfacePackageReady(SurfaceControlViewHost.SurfacePackage surfacePackage,
                int surfacePackageId, Bundle params) {
            sendSurfacePackageReadyToApp(surfacePackage, surfacePackageId, params);
        }

        @Override
        public void onSurfacePackageError(int errorCode, String errorMsg) {
            sendSurfacePackageErrorToApp(errorCode, errorMsg);
        }

        private void sendLoadSdkSuccessToApp(Bundle params) {
            try {
                mManagerToAppCallback.onLoadSdkSuccess(mSdkToken, params);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to send onLoadCodeSuccess", e);
            }
        }

        void sendLoadSdkErrorToApp(int errorCode, String errorMsg) {
            // Since loadSdk failed, manager should no longer concern itself with communication
            // between the app and a non-existing remote code.
            cleanUp(mSdkToken);

            try {
                mManagerToAppCallback.onLoadSdkFailure(errorCode, errorMsg);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to send onLoadCodeFailure", e);
            }
        }

        void sendSurfacePackageErrorToApp(int errorCode, String errorMsg) {
            try {
                mManagerToAppCallback.onSurfacePackageError(errorCode, errorMsg);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to send onSurfacePackageError", e);
            }
        }

        private void sendSurfacePackageReadyToApp(
                SurfaceControlViewHost.SurfacePackage surfacePackage,
                int surfacePackageId, Bundle params) {
            try {
                mManagerToAppCallback.onSurfacePackageReady(surfacePackage,
                        surfacePackageId, params);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to send onSurfacePackageReady callback", e);
            }
        }

        void requestSurfacePackageToCode(IBinder hostToken, int displayId, Bundle params) {
            try {
                synchronized (this) {
                    mManagerToCodeCallback.onSurfacePackageRequested(hostToken, displayId, params);
                }
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to requestSurfacePackage", e);
                // TODO(b/204991850): send request surface package error back to app
            }
        }
    }

    /** @hide */
    public static class Lifecycle extends SystemService {
        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            SdkSandboxServiceProvider provider =
                    new SdkSandboxServiceProviderImpl(getContext());
            SdkSandboxManagerService service =
                    new SdkSandboxManagerService(getContext(), provider);
            publishBinderService(SDK_SANDBOX_SERVICE, service);
        }
    }
}
