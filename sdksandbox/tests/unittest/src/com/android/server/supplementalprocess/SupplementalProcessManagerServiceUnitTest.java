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

import static android.os.Process.myUserHandle;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.supplementalprocess.IRemoteCodeCallback;
import android.supplementalprocess.SupplementalProcessManager;
import android.view.SurfaceControlViewHost;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link SupplementalProcessManagerService}.
 */
public class SupplementalProcessManagerServiceUnitTest {

    private SupplementalProcessManagerService mService;

    private static final String CODE_PROVIDER_PACKAGE = "com.android.codeprovider";

    @Before
    public void setup() {
        Context context = InstrumentationRegistry.getContext();
        // TODO(b/204991850): Replace with fake provider
        SupplementalProcessServiceProvider provider =
                new SupplementalProcessServiceProviderImpl(context);
        mService = new SupplementalProcessManagerService(context, provider);
    }


    // TODO(b/207771670): Move this test to SupplementalProcessServiceProviderUnitTest
    // TODO(b/204991850): Bind to test version of suppl. process instead of the real one
    @Test
    public void testSupplementalProcessBinding() throws Exception {
        UserHandle curUser = myUserHandle();

        // Supplemental process is loaded on demand, so should not be there initially
        assertThat(mService.isSupplementalProcessBound(curUser)).isFalse();
        FakeInitCodeCallback callback = new FakeInitCodeCallback();
        mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
        assertThat(mService.isSupplementalProcessBound(curUser)).isTrue();
    }

    @Test
    public void testLoadCodeIsSuccessful() throws Exception {
        FakeInitCodeCallback callback = new FakeInitCodeCallback();
        mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
        assertThat(callback.isLoadCodeSuccessful()).isTrue();
    }

    @Test
    public void testLoadCodePackageDoesNotExist() throws Exception {
        FakeInitCodeCallback callback = new FakeInitCodeCallback();
        mService.loadCode("does.not.exist", "1", new Bundle(), callback);

        // Verify loading failed
        assertThat(callback.isLoadCodeSuccessful()).isFalse();
        assertThat(callback.getErrorCode()).isEqualTo(
                SupplementalProcessManager.LOAD_CODE_NOT_FOUND);
        assertThat(callback.getErrorMsg()).contains("not found for loading");
    }

    @Test
    public void testRequestSurfacePackageCodeNotLoaded() throws Exception {
        // Trying to request package without using proper codeToken should fail
        SecurityException thrown = assertThrows(
                SecurityException.class,
                () -> mService.requestSurfacePackage(new Binder(), new Binder(),
                                                     0, new Bundle())
        );
        assertThat(thrown).hasMessageThat().contains("codeToken is invalid");
    }

    @Test
    public void testRequestSurfacePackage() throws Exception {
        // 1. We first need to collect a proper codeToken by calling loadCode

        FakeInitCodeCallback callback = new FakeInitCodeCallback();
        mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
        assertThat(callback.isLoadCodeSuccessful()).isTrue();

        // Verify codeToken is not null
        IBinder codeToken = callback.getCodeToken();
        assertThat(codeToken).isNotNull();

        // 2. Call request package with the retrieved codeToken
        mService.requestSurfacePackage(codeToken, new Binder(), 0, new Bundle());
        assertThat(callback.isRequestSurfacePackageSuccessful()).isTrue();
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

        int getErrorCode() {
            waitForLatch(mLoadCodeLatch);
            assertThat(mLoadCodeSuccess).isFalse();
            return mErrorCode;
        }

        String getErrorMsg() {
            waitForLatch(mLoadCodeLatch);
            assertThat(mLoadCodeSuccess).isFalse();
            return mErrorMsg;
        }

        IBinder getCodeToken() {
            waitForLatch(mLoadCodeLatch);
            assertThat(mLoadCodeSuccess).isTrue();
            return mCodeToken;
        }

        boolean isRequestSurfacePackageSuccessful() throws InterruptedException {
            waitForLatch(mSurfacePackageLatch);
            return mSurfacePackageSuccess;
        }

    }
}
