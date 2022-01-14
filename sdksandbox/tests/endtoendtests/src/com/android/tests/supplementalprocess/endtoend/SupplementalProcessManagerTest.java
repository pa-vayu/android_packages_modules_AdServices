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

package com.android.tests.supplementalprocess.endtoend;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.supplementalprocess.IRemoteCodeCallback;
import android.supplementalprocess.SupplementalProcessManager;
import android.util.Log;
import android.view.SurfaceControlViewHost;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public class SupplementalProcessManagerTest {

    private SupplementalProcessManager mSupplementalProcessManager;
    private static final String CODE_PROVIDER_KEY = "code-provider-class";

    @Before
    public void setup() {
        Context context = InstrumentationRegistry.getContext();
        mSupplementalProcessManager = context.getSystemService(SupplementalProcessManager.class);
    }

    @Test
    public void checkSuccessfulCallback() throws Exception {
        Bundle params = new Bundle();
        params.putString(CODE_PROVIDER_KEY,
                "com.android.supplementalprocesscode.SampleCodeProvider");
        FakeInitCodeCallback callback = new FakeInitCodeCallback();
        mSupplementalProcessManager.loadCode(
                "com.android.supplementalprocesscode", "1", params, callback);
        assertThat(callback.isLoadCodeSuccessful()).isTrue();
    }

    private static class FakeInitCodeCallback extends IRemoteCodeCallback.Stub {
        private final CountDownLatch mLoadCodeLatch = new CountDownLatch(1);
        private final CountDownLatch mSurfacePackageLatch = new CountDownLatch(1);

        private boolean mLoadCodeSuccess;
        private boolean mSurfacePackageSuccess;

        private int mErrorCode;
        private String mErrorMsg;

        private IBinder mCodeToken;

        @Override
        public void onLoadCodeSuccess(IBinder codeToken, Bundle params) {
            mCodeToken = codeToken;
            mLoadCodeSuccess = true;
            mLoadCodeLatch.countDown();
        }

        @Override
        public void onLoadCodeFailure(int errorCode, String errorMsg) {
            mLoadCodeSuccess = false;
            mErrorCode = errorCode;
            mErrorMsg = errorMsg;
            mLoadCodeLatch.countDown();
        }
        @Override
        public void onSurfacePackageError(int errorCode, String errorMsg) {
            mSurfacePackageSuccess = false;
            mErrorCode = errorCode;
            mErrorMsg = errorMsg;
            mSurfacePackageLatch.countDown();
        }

        @Override
        public void onSurfacePackageReady(SurfaceControlViewHost.SurfacePackage surfacePackage,
                int surfacePackageId, Bundle params) {
            mSurfacePackageSuccess = true;
            mSurfacePackageLatch.countDown();
        }

        void waitForLatch(CountDownLatch latch) {
            try {
                // Wait for callback to be called
                if (!latch.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Callback not called within 2 seconds");
                }
            } catch (InterruptedException e) {
                throw new IllegalStateException(
                        "Interrupted while waiting on callback: " + e.getMessage());
            }
        }

        boolean isLoadCodeSuccessful() throws InterruptedException {
            waitForLatch(mLoadCodeLatch);
            return mLoadCodeSuccess;
        }
    }
}
