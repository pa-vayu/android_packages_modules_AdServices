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

package android.supplementalprocess.testutils;

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;
import android.os.IBinder;
import android.supplementalprocess.IRemoteCodeCallback;
import android.view.SurfaceControlViewHost;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class FakeRemoteCodeCallback extends IRemoteCodeCallback.Stub {
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

    public boolean isLoadCodeSuccessful() throws InterruptedException {
        waitForLatch(mLoadCodeLatch);
        return mLoadCodeSuccess;
    }

    public IBinder getCodeToken() {
        waitForLatch(mLoadCodeLatch);
        assertThat(mLoadCodeSuccess).isTrue();
        return mCodeToken;
    }

    public int getLoadCodeErrorCode() {
        waitForLatch(mLoadCodeLatch);
        assertThat(mLoadCodeSuccess).isFalse();
        return mErrorCode;
    }

    public String getLoadCodeErrorMsg() {
        waitForLatch(mLoadCodeLatch);
        assertThat(mLoadCodeSuccess).isFalse();
        return mErrorMsg;
    }

    public boolean isRequestSurfacePackageSuccessful() throws InterruptedException {
        waitForLatch(mSurfacePackageLatch);
        return mSurfacePackageSuccess;
    }

    public int getSurfacePackageErrorCode() {
        waitForLatch(mSurfacePackageLatch);
        assertThat(mSurfacePackageSuccess).isFalse();
        return mErrorCode;
    }

    public String getSurfacePackageErrorMsg() {
        waitForLatch(mSurfacePackageLatch);
        assertThat(mSurfacePackageSuccess).isFalse();
        return mErrorMsg;
    }

    private void waitForLatch(CountDownLatch latch) {
        try {
            // Wait for callback to be called
            final int waitTime = 5;
            if (!latch.await(waitTime, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                    "Callback not called within " + waitTime + " seconds");
            }
        } catch (InterruptedException e) {
            throw new IllegalStateException(
                    "Interrupted while waiting on callback: " + e.getMessage());
        }
    }
}
