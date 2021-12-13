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

package android.supplementalprocess;

import android.annotation.SystemService;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

/**
 * Supplemental Process Manager.
 * @hide
 */
@SystemService(Context.SUPPLEMENTAL_PROCESS_SERVICE)
public class SupplementalProcessManager {
    private static final String TAG = "SupplementalProcessManager";

    private final ISupplementalProcessManager mService;
    private final Context mContext;

    public SupplementalProcessManager(Context context, ISupplementalProcessManager binder) {
        mContext = context;
        mService = binder;
    }

    /**
     * Fetches and loads code into supplemental process.
     */
    public void loadCode(String name, String version, Bundle params, IRemoteCodeCallback callback) {
        try {
            mService.loadCode(name, version, params, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sends a request for a surface package to the remote code.
     */
    public void requestSurfacePackage(IBinder codeToken, IBinder hostToken, int displayId,
            Bundle params) {
        try {
            mService.requestSurfacePackage(codeToken, hostToken, displayId, params);
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

    /**
     * Destroys the code that is loaded into supplemental process.
     */
    public void destroyCode(int id) {
        try {
            mService.destroyCode(id);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Error code to represent that there is no such code
     */
    public static final int LOAD_CODE_NOT_FOUND = 100;

    public static final int LOAD_CODE_INTERNAL_ERROR = 500;

    public static final int SURFACE_PACKAGE_INTERNAL_ERROR = 700;
}
