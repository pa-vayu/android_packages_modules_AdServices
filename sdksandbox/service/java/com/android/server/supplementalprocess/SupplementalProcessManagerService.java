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
import android.os.UserHandle;
import android.supplementalprocess.IRemoteCodeCallback;
import android.supplementalprocess.ISupplementalProcessManager;
import android.supplementalprocess.SupplementalProcessManager;
import android.util.ArrayMap;
import android.util.Log;
import android.view.SurfaceControlViewHost;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;
import com.android.supplemental.process.ISupplementalProcessManagerToSupplementalProcessCallback;
import com.android.supplemental.process.ISupplementalProcessToSupplementalProcessManagerCallback;

/**
 * Implementation of Supplemental Process Manager service.
 *
 * @hide
 */
public class SupplementalProcessManagerService extends ISupplementalProcessManager.Stub {

    private static final String TAG = "SupplementalProcessManager";

    private final Context mContext;
    private final SupplementalProcessServiceProvider mServiceProvider;

    // TODO(b/204991850): guard by lock
    // For one way communication for ManagerService to app for each codeToken
    private final ArrayMap<IBinder, IRemoteCodeCallback> mCallbackToApp = new ArrayMap<>();
    // TODO(b/204991850): guard by lock
    // For two way communication between ManagerService and remote code for each codeToken
    private final ArrayMap<IBinder, TwoWayCallback> mTwoWayCallbckToRemoteCode = new ArrayMap();

    SupplementalProcessManagerService(Context context,
            SupplementalProcessServiceProvider provider) {
        mContext = context;
        mServiceProvider = provider;
    }

    @Override
    public void loadCode(String name, String version, Bundle params, IRemoteCodeCallback callback) {
        final UserHandle callingUser = Binder.getCallingUserHandle();
        final long token = Binder.clearCallingIdentity();
        try {
            loadCodeWithClearIdentity(callingUser, name, version, params, callback);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void loadCodeWithClearIdentity(UserHandle callingUser, String name, String version,
            Bundle params, IRemoteCodeCallback callback) {
        // Step 1: fetch the installed code in device

        final ApplicationInfo info = getCodeInfo(name);
        if (info == null) {
            String errorMsg = name + " not found for loading";
            Log.w(TAG, errorMsg);
            sendLoadCodeError(SupplementalProcessManager.LOAD_CODE_NOT_FOUND, errorMsg, callback);
            return;
        }
        // TODO(b/204991850): ensure requested code is included in the AndroidManifest.xml

        // Step 2: create identity for the code
        //TODO(b/204991850): <app,code> unit should get unique token
        IBinder codeToken = new Binder();
        mCallbackToApp.put(codeToken, callback);

        // Step 3: invoke CodeLoaderService to load the code
        mServiceProvider.bindService(callingUser);
        TwoWayCallback twoWayCallback = new TwoWayCallback(codeToken);
        mTwoWayCallbckToRemoteCode.put(codeToken, twoWayCallback);
        try {
            mServiceProvider.getService(callingUser).loadCode(codeToken, info, params,
                    twoWayCallback);
        } catch (RemoteException e) {
            String errorMsg = "Failed to contact SupplementalProcessService";
            Log.w(TAG, errorMsg, e);
            sendLoadCodeError(SupplementalProcessManager.LOAD_CODE_INTERNAL_ERROR,
                    errorMsg, callback);
        }
    }

    /**
     * A callback object to establish a two-way communication channel between
     * SupplementalProcessManagerService and remote code loaded in SupplementalProcess.
     *
     * This object provides interface to remote code to callback into
     * SupplementalProcessManager. During loadCode, remote code calls back with a callback
     * which allows communication in the other direction.
     *
     * This bidirectional channel is maintained for each {@code codeToken} generated.
     */
    private class TwoWayCallback extends
            ISupplementalProcessToSupplementalProcessManagerCallback.Stub {
        // The codeToken for which this channel has been created
        private final IBinder mCodeToken;
        private ISupplementalProcessManagerToSupplementalProcessCallback mManagerToCodeCallback;

        TwoWayCallback(IBinder codeToken) {
            mCodeToken = codeToken;
        }

        @Override
        public void onLoadCodeSuccess(Bundle params,
                ISupplementalProcessManagerToSupplementalProcessCallback callback) {
            // Keep reference to callback so that manager service can
            // callback to remote code loaded.
            mManagerToCodeCallback = callback;
            sendLoadCodeSuccess(mCodeToken, params);
        }

        @Override
        public void onLoadCodeError(int errorCode, String errorMsg) {}

        @Override
        public void onSurfacePackageReady(SurfaceControlViewHost.SurfacePackage surfacePackage,
                int surfacePackageId, Bundle params) {
            sendSurfacePackageReady(mCodeToken, surfacePackage, surfacePackageId, params);
        }

        ISupplementalProcessManagerToSupplementalProcessCallback getManagerToRemoteCodeCallback() {
            return mManagerToCodeCallback;
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

    private void sendLoadCodeSuccess(IBinder codeToken, Bundle params) {
        try {
            mCallbackToApp.get(codeToken).onLoadCodeSuccess(codeToken, params);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to send onLoadCodeSuccess", e);
        }
    }

    private void sendLoadCodeError(int errorCode, String errorMsg, IRemoteCodeCallback callback) {
        try {
            callback.onLoadCodeFailure(errorCode, errorMsg);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to send onLoadCodeFailure", e);
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
        if (!mTwoWayCallbckToRemoteCode.containsKey(codeToken)) {
            throw new SecurityException("codeToken is invalid");
        }
        TwoWayCallback twoWayCallback = mTwoWayCallbckToRemoteCode.get(codeToken);
        try {
            twoWayCallback.getManagerToRemoteCodeCallback()
                    .onSurfacePackageRequested(hostToken, displayId, params);
        } catch (RemoteException e) {
            //TODO(b/204991850): sendSurfacePackageError
            Log.w(TAG, "Failed to request surface package", e);
        }
    }

    void sendSurfacePackageReady(IBinder codeToken,
            SurfaceControlViewHost.SurfacePackage surfacePackage,
            int surfacePackageId, Bundle params) {
        try {
            mCallbackToApp.get(codeToken).onSurfacePackageReady(
                    surfacePackage, surfacePackageId, params);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to send onSurfacePackageReady callback", e);
        }
    }

    @Override
    public void sendData(int id, Bundle params) {}

    @Override
    public void destroyCode(int id) {}

    // TODO(b/207771670): This API should be reomoved once we have unit test for
    //  SupplementalProcessServiceProvider
    @VisibleForTesting
    boolean isSupplementalProcessBound(UserHandle callingUser) {
        return mServiceProvider.isServiceBound(callingUser);
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
