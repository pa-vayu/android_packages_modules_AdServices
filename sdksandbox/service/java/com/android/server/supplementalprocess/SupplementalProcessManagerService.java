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
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.supplementalprocess.IInitCodeCallback;
import android.supplementalprocess.ISupplementalProcessManager;
import android.util.Log;

import com.android.server.SystemService;

/**
 * Implementation of Supplemental Process Manager service.
 *
 * @hide
 */
public class SupplementalProcessManagerService extends ISupplementalProcessManager.Stub {

    private static final String TAG = "SupplementalProcessManager";

    private final Context mContext;

    SupplementalProcessManagerService(Context context) {
        mContext = context;
    }

    @Override
    public void loadCode(String name, String version, Bundle params, IInitCodeCallback callback) {
        // Barebone logic for loading code. Still incomplete.

        // TODO(b/204991850): ensure code exists
        // TODO(b/204991850): ensure requested code is included in the AndroidManifest.xml

        // TODO(b/204991850): actually load the code

        //TODO(b/204991850): <app,code> unit should get unique token
        sendLoadCodeSuccess(new Binder(), callback);
    }

    private void sendLoadCodeSuccess(IBinder token, IInitCodeCallback callback) {
        try {
            //TODO(b/204991850): params should be returned from SupplementalProcessService
            callback.onInitCodeSuccess(token, new Bundle());
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to send onInitCodeFinished", e);
        }
    }

    @Override
    public void requestSurfacePackage(int id, IBinder token, int displayId, Bundle params) {}

    @Override
    public void sendData(int id, Bundle params) {}

    @Override
    public void destroyCode(int id) {}

    /** @hide */
    public static class Lifecycle extends SystemService {
        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            SupplementalProcessManagerService service =
                    new SupplementalProcessManagerService(getContext());
            publishBinderService(Context.SUPPLEMENTAL_PROCESS_SERVICE, service);
            Log.i(TAG, "SupplementalProcessManagerService started!");
        }
    }
}
