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

package com.android.supplemental.process;

import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

/**
 * A holder for loaded code.
 */
class CodeHolder {

    private static final String TAG = "SupplementalProcess";

    private boolean mInitialized = false;
    private ISupplementalProcessToSupplementalProcessManagerCallback mCallback;

    void init(Context context, Bundle params,
            ISupplementalProcessToSupplementalProcessManagerCallback callback) {
        if (mInitialized) {
            throw new IllegalStateException("Already initialized!");
        }
        mInitialized = true;
        mCallback = callback;
        sendLoadCodeSuccess();
    }

    private void sendLoadCodeSuccess() {
        try {
            // TODO(b/204989872): return a
            // ISupplementalProcessManagerToSupplementalProcessCallback from here
            mCallback.onLoadCodeSuccess(new Bundle(), /*callback=*/null);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not send onLoadCodeSuccess");
        }
    }
}
