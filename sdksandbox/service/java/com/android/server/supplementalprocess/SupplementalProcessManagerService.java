/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.supplementalprocess;

import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.supplementalprocess.IRemoteCodeCallback;
import android.supplementalprocess.ISupplementalProcessManager;
import android.supplementalprocess.SupplementalProcessManager;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.view.SurfaceControlViewHost;

import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;
import com.android.supplemental.process.ISupplementalProcessManagerToSupplementalProcessCallback;
import com.android.supplemental.process.ISupplementalProcessService;
import com.android.supplemental.process.ISupplementalProcessToSupplementalProcessManagerCallback;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Implementation of Supplemental Process Manager service.
 *
 * @hide
 */
public class SupplementalProcessManagerService extends ISupplementalProcessManager.Stub {

    private static final String TAG = "SupplementalProcessManager";

    private final Context mContext;
    private final CodeTokenManager mCodeTokenManager = new CodeTokenManager();

    private final SupplementalProcessServiceProvider mServiceProvider;

    // For communication between app<-ManagerService->RemoteCode for each codeToken
    // TODO(b/208824602): Remove from this map when an app dies.
    @GuardedBy("mAppAndRemoteCodeLinks")
    private final ArrayMap<IBinder, AppAndRemoteCodeLink> mAppAndRemoteCodeLinks = new ArrayMap();

    SupplementalProcessManagerService(Context context,
            SupplementalProcessServiceProvider provider) {
        mContext = context;
        mServiceProvider = provider;
    }

    @Override
    public void loadCode(String name, String version, Bundle params, IRemoteCodeCallback callback) {
        final int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            loadCodeWithClearIdentity(callingUid, name, params, callback);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void loadCodeWithClearIdentity(int callingUid, String name, Bundle params,
            IRemoteCodeCallback callback) {
        // Step 1: create unique identity for the {callingUid, name} pair
        final IBinder codeToken = mCodeTokenManager.createOrGetCodeToken(callingUid, name);

        // Ensure we are not already loading code for this codeToken. That's determined by
        // checking if we already have an AppAndRemoteCodeLink for the codeToken.
        final AppAndRemoteCodeLink link = new AppAndRemoteCodeLink(codeToken, callback);
        synchronized (mAppAndRemoteCodeLinks) {
            if (mAppAndRemoteCodeLinks.putIfAbsent(codeToken, link) != null) {
                link.sendLoadCodeErrorToApp(SupplementalProcessManager.LOAD_CODE_ALREADY_LOADED,
                        name + " is being loaded or has been loaded already");
                return;
            }
        }
        // Step 2: fetch the installed code in device
        final ApplicationInfo info = getCodeInfo(name);
        if (info == null) {
            String errorMsg = name + " not found for loading";
            Log.w(TAG, errorMsg);
            link.sendLoadCodeErrorToApp(SupplementalProcessManager.LOAD_CODE_NOT_FOUND, errorMsg);
            return;
        }
        // TODO(b/204991850): ensure requested code is included in the AndroidManifest.xml

        // Step 3: invoke CodeLoaderService to load the code
        try {
            ISupplementalProcessService service =
                    mServiceProvider.bindService(callingUid);
            try {
                // TODO(b/209621566): Remove the need for this crude workaround.
                Thread.sleep(1000);
            } catch (Exception e) {
                Log.e(TAG, "Could not sleep" , e);
            }
            if (service == null) {
                link.sendLoadCodeErrorToApp(
                        SupplementalProcessManager.LOAD_CODE_INTERNAL_ERROR,
                        "Failed to bind to SupplementalProcess service");
                return;
            }
            // TODO(b/208631926): Pass a meaningful value for codeProviderClassName
            service.loadCode(codeToken, info, "", params, link);
        } catch (RemoteException e) {
            String errorMsg = "Failed to contact SupplementalProcessService";
            Log.w(TAG, errorMsg, e);
            link.sendLoadCodeErrorToApp(SupplementalProcessManager.LOAD_CODE_INTERNAL_ERROR,
                    errorMsg);
        }

        // Register a death recipient to clean up codeToken and unbind its service after app dies.
        try {
            callback.asBinder().linkToDeath(() -> {
                cleanUp(codeToken);
                mServiceProvider.unbindService(callingUid);
            }, 0);
        } catch (RemoteException re) {
            // App has already died, cleanup code token and link, and unbind its service
            cleanUp(codeToken);
            mServiceProvider.unbindService(callingUid);
        }
    }

    private ApplicationInfo getCodeInfo(String packageName) {
        // TODO(b/204991850): code info should be version specific too
        try {
            // TODO(b/204991850): update this when PM provides better API for getting code info
            return mContext.getPackageManager().getApplicationInfo(packageName, /*flags=*/0);
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    @Override
    public void requestSurfacePackage(IBinder codeToken, IBinder hostToken,
                int displayId, Bundle params) {
        //TODO(b/204991850): verify that codeToken belongs to the callingUid
        final long token = Binder.clearCallingIdentity();
        try {
            requestSurfacePackageWithClearIdentity(codeToken,
                    hostToken, displayId, params);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void requestSurfacePackageWithClearIdentity(IBinder codeToken,
            IBinder hostToken, int displayId, Bundle params) {
        synchronized (mAppAndRemoteCodeLinks) {
            if (!mAppAndRemoteCodeLinks.containsKey(codeToken)) {
                throw new SecurityException("codeToken is invalid");
            }
            final AppAndRemoteCodeLink link = mAppAndRemoteCodeLinks.get(codeToken);
            link.requestSurfacePackageToCode(hostToken, displayId, params);
        }
    }

    @Override
    public void sendData(int id, Bundle params) {}

    @Override
    public void destroyCode(int id) {}

    @Override
    @RequiresPermission(android.Manifest.permission.DUMP)
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mContext.enforceCallingPermission(android.Manifest.permission.DUMP,
                "Can't dump " + TAG);

        // TODO(b/211575098): Use IndentingPrintWriter for better formatting
        synchronized (mAppAndRemoteCodeLinks) {
            writer.println("mAppAndRemoteCodeLinks size: " + mAppAndRemoteCodeLinks.size());
        }

        writer.println("mCodeTokenManager:");
        mCodeTokenManager.dump(writer);
        writer.println();

        writer.println("mServiceProvider:");
        mServiceProvider.dump(writer);
        writer.println();
    }

    /**
     * Clean up all internal data structures related to {@code codeToken}
     */
    private void cleanUp(IBinder codeToken) {
        // Destroy the codeToken first, to free up the {callingUid, name} pair
        mCodeTokenManager.destroy(codeToken);
        // Now clean up rest of the state which is using an obsolete codeToken
        synchronized (mAppAndRemoteCodeLinks) {
            mAppAndRemoteCodeLinks.remove(codeToken);
        }
    }

    @ThreadSafe
    private static class CodeTokenManager {
        // Keep track of codeToken for each unique pair of {callingUid, name}
        @GuardedBy("mCodeTokens")
        final ArrayMap<Pair<Integer, String>, IBinder> mCodeTokens = new ArrayMap<>();
        @GuardedBy("mCodeTokens")
        final ArrayMap<IBinder, Pair<Integer, String>> mReverseCodeTokens = new ArrayMap<>();

        /**
         * For the given {callingUid, name} pair, create unique codeToken or
         * return existing one.
         */
        public IBinder createOrGetCodeToken(int callingUid, String name) {
            final Pair<Integer, String> pair = Pair.create(callingUid, name);
            synchronized (mCodeTokens) {
                if (!mCodeTokens.containsKey(pair)) {
                    final IBinder codeToken = new Binder();
                    mCodeTokens.put(pair, codeToken);
                    mReverseCodeTokens.put(codeToken, pair);
                }
                return mCodeTokens.get(pair);
            }
        }

        public void destroy(IBinder codeToken) {
            synchronized (mCodeTokens) {
                mCodeTokens.remove(mReverseCodeTokens.get(codeToken));
                mReverseCodeTokens.remove(codeToken);
            }
        }

        void dump(PrintWriter writer) {
            synchronized (mCodeTokens) {
                if (mCodeTokens.isEmpty()) {
                    writer.println("mCodeTokens is empty");
                } else {
                    writer.print("mCodeTokens size: ");
                    writer.println(mCodeTokens.size());
                    for (Pair<Integer, String> pair : mCodeTokens.keySet()) {
                        writer.printf("callingUid: %s, name: %s", pair.first, pair.second);
                        writer.println();
                    }
                }
            }
        }
    }

    /**
     * A callback object to establish a link between the app calling into manager service
     * and the remote code being loaded in SupplementalProcess.
     *
     * Overview of communication:
     * 1. App to ManagerService: App calls into this service via app context
     * 2. ManagerService to App: {@link AppAndRemoteCodeLink} holds reference to
     *    {@link IRemoteCodeCallback} object which provides call back into the app.
     * 3. RemoteCode to ManagerService: {@link AppAndRemoteCodeLink} extends
     *    {@link ISupplementalProcessToSupplementalProcessManagerCallback} interface. We
     *    pass on this object to {@link ISupplementalProcessService} so that remote code
     *    can call back into ManagerService
     * 4. ManagerService to RemoteCode: When code is loaded for the first time and remote
     *    code calls back with successful result, it also sends reference to
     *    {@link ISupplementalProcessManagerToSupplementalProcessCallback} callback object.
     *    ManagerService uses this to callback into the remote code.
     *
     * We maintain a link for each unique {app, remoteCode} pair, which is identified with
     * {@code codeToken}.
     */
    private class AppAndRemoteCodeLink extends
            ISupplementalProcessToSupplementalProcessManagerCallback.Stub {
        // The codeToken for which this channel has been created
        private final IBinder mCodeToken;
        private final IRemoteCodeCallback mManagerToAppCallback;

        @GuardedBy("this")
        private ISupplementalProcessManagerToSupplementalProcessCallback mManagerToCodeCallback;

        AppAndRemoteCodeLink(IBinder codeToken, IRemoteCodeCallback managerToAppCallback) {
            mCodeToken = codeToken;
            mManagerToAppCallback = managerToAppCallback;
        }

        @Override
        public void onLoadCodeSuccess(Bundle params,
                ISupplementalProcessManagerToSupplementalProcessCallback callback) {
            // Keep reference to callback so that manager service can
            // callback to remote code loaded.
            synchronized (this) {
                mManagerToCodeCallback = callback;
            }
            sendLoadCodeSuccessToApp(params);
        }

        @Override
        public void onLoadCodeError(int errorCode, String errorMsg) {
            sendLoadCodeErrorToApp(errorCode, errorMsg);
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

        private void sendLoadCodeSuccessToApp(Bundle params) {
            try {
                mManagerToAppCallback.onLoadCodeSuccess(mCodeToken, params);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to send onLoadCodeSuccess", e);
            }
        }

        void sendLoadCodeErrorToApp(int errorCode, String errorMsg) {
            // Since loadCode failed, manager should no longer concern itself with communication
            // between the app and a non-existing remote code.
            cleanUp(mCodeToken);

            try {
                mManagerToAppCallback.onLoadCodeFailure(errorCode, errorMsg);
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
            SupplementalProcessServiceProvider provider =
                    new SupplementalProcessServiceProviderImpl(getContext());
            SupplementalProcessManagerService service =
                    new SupplementalProcessManagerService(getContext(), provider);
            publishBinderService(Context.SUPPLEMENTAL_PROCESS_SERVICE, service);
            Log.i(TAG, "SupplementalProcessManagerService started!");
        }
    }
}
