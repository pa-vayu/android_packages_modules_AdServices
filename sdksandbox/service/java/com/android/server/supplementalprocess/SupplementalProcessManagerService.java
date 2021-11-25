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
import android.view.SurfaceControlViewHost;

import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;
import com.android.supplemental.process.ISupplementalProcessManagerToSupplementalProcessCallback;
import com.android.supplemental.process.ISupplementalProcessService;
import com.android.supplemental.process.ISupplementalProcessToSupplementalProcessManagerCallback;

/**
 * Implementation of Supplemental Process Manager service.
 *
 * @hide
 */
public class SupplementalProcessManagerService extends ISupplementalProcessManager.Stub {

    private static final String TAG = "SupplementalProcessManager";

    private final Context mContext;

    @GuardedBy("mServiceProvider")
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
            loadCodeWithClearIdentity(callingUid, name, version, params, callback);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void loadCodeWithClearIdentity(int callingUid, String name, String version,
            Bundle params, IRemoteCodeCallback callback) {
        // Step 1: create identity for the code
        // TODO(b/204991850): what if app tries to load same code twice? Identity should be
        // unique for each <uid, code> pair.
        final IBinder codeToken = new Binder();
        // Use the code token to establish a link between the app<-Manager->RemoteCode
        final AppAndRemoteCodeLink link = new AppAndRemoteCodeLink(codeToken, callback);
        synchronized (mAppAndRemoteCodeLinks) {
            mAppAndRemoteCodeLinks.put(codeToken, link);
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
            synchronized (mServiceProvider) {
                // TODO(b/204991850): we should merge bindService() and getService() together
                mServiceProvider.bindService(callingUid, codeToken);
                ISupplementalProcessService service = mServiceProvider.getService(callingUid);
                if (service == null) {
                    link.sendLoadCodeErrorToApp(SupplementalProcessManager.LOAD_CODE_INTERNAL_ERROR,
                            "Failed to bind to SupplementalProcess service");
                    return;
                }
                // TODO(b/208631926): Pass a meaningful value for codeProviderClassName
                service.loadCode(codeToken, info, "", params, link);
            }
        } catch (RemoteException e) {
            String errorMsg = "Failed to contact SupplementalProcessService";
            Log.w(TAG, errorMsg, e);
            link.sendLoadCodeErrorToApp(SupplementalProcessManager.LOAD_CODE_INTERNAL_ERROR,
                    errorMsg);
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
        //TODO(b/204991850): verify that codeToken belongs to the callingUser
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
     * 4. ManagerService to RemoteCode: When cod is loaded for the first time and remote
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
        public void onLoadCodeError(int errorCode, String errorMsg) {}

        @Override
        public void onSurfacePackageReady(SurfaceControlViewHost.SurfacePackage surfacePackage,
                int surfacePackageId, Bundle params) {
            sendSurfacePackageReadyToApp(surfacePackage, surfacePackageId, params);
        }

        private void sendLoadCodeSuccessToApp(Bundle params) {
            try {
                mManagerToAppCallback.onLoadCodeSuccess(mCodeToken, params);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to send onLoadCodeSuccess", e);
            }
        }

        void sendLoadCodeErrorToApp(int errorCode, String errorMsg) {
            try {
                mManagerToAppCallback.onLoadCodeFailure(errorCode, errorMsg);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to send onLoadCodeFailure", e);
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
                sendLoadCodeErrorToApp(SupplementalProcessManager.LOAD_CODE_INTERNAL_ERROR,
                        "Failed to requestSurfacePackage" + e.getMessage());
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
