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

package android.app.sdksandbox;

import static android.app.sdksandbox.SdkSandboxManager.SDK_SANDBOX_SERVICE;

import android.annotation.SystemService;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

/**
 * Sdk Sandbox manager.
 *
 * @hide
 */
@SystemService(SDK_SANDBOX_SERVICE)
public class SdkSandboxManager {

    public static final String SDK_SANDBOX_SERVICE = "sdk_sandbox";

    private final ISdkSandboxManager mService;
    private final Context mContext;

    public SdkSandboxManager(Context context, ISdkSandboxManager binder) {
        mContext = context;
        mService = binder;
    }

    /**
     * Fetches and loads sdk into the sdk sandbox.
     */
    public void loadSdk(String name, String version, Bundle params, IRemoteSdkCallback callback) {
        try {
            mService.loadSdk(name, version, params, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sends a request for a surface package to the remote sdk.
     */
    public void requestSurfacePackage(IBinder sdkToken, IBinder hostToken, int displayId,
            Bundle params) {
        try {
            mService.requestSurfacePackage(sdkToken, hostToken, displayId, params);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sends a bundle to supplemental process.
     */
    public void sendData(int id, Bundle params) {
        try {
            mService.sendData(id, params);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Error code to represent that there is no such code */
    public static final int LOAD_SDK_SDK_NOT_FOUND = 100;

    /** Error code to represent code is already loaded */
    public static final int LOAD_SDK_SDK_ALREADY_LOADED = 101;

    public static final int LOAD_SDK_INTERNAL_ERROR = 500;

    public static final int SURFACE_PACKAGE_INTERNAL_ERROR = 700;
}
