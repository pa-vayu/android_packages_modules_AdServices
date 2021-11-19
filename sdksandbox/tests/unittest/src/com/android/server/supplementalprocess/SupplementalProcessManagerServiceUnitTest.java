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

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;
import android.os.IBinder;
import android.supplementalprocess.IInitCodeCallback;
import android.supplementalprocess.SupplementalProcessManager;

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
        mService = new SupplementalProcessManagerService(InstrumentationRegistry.getContext());
    }

    @Test
    public void testLoadCodeIsSuccessful() throws Exception {
        FakeInitCodeCallback callback = new FakeInitCodeCallback();
        mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
        assertThat(callback.isInitCodeSuccessful()).isTrue();
    }

    @Test
    public void testLoadCodePackageDoesNotExist() throws Exception {
        FakeInitCodeCallback callback = new FakeInitCodeCallback();
        mService.loadCode("does.not.exist", "1", new Bundle(), callback);

        // Verify loading failed
        assertThat(callback.isInitCodeSuccessful()).isFalse();
        assertThat(callback.getErrorCode()).isEqualTo(
                SupplementalProcessManager.LOAD_CODE_NOT_FOUND);
        assertThat(callback.getErrorMsg()).contains("not found for loading");
    }

    private static class FakeInitCodeCallback extends IInitCodeCallback.Stub {
        private final CountDownLatch mCallbackLatch = new CountDownLatch(1);
        private boolean mSuccess;
        private int mErrorCode;
        private String mErrorMsg;

        @Override
        public void onInitCodeSuccess(IBinder token, Bundle params) {
            mSuccess = true;
            mCallbackLatch.countDown();
        }

        @Override
        public void onInitCodeFailure(int errorCode, String errorMsg) {
            mSuccess = false;
            mErrorCode = errorCode;
            mErrorMsg = errorMsg;
            mCallbackLatch.countDown();
        }

        void waitForCallback() {
            try {
                // Wait for callback to be called
                if (!mCallbackLatch.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Callback not called within 2 seconds");
                }
            } catch (InterruptedException e) {
                throw new IllegalStateException(
                        "Interrupted while waiting on callback: " + e.getMessage());
            }
        }

        boolean isInitCodeSuccessful() throws InterruptedException {
            waitForCallback();
            return mSuccess;
        }

        int getErrorCode() {
            waitForCallback();
            assertThat(mSuccess).isFalse();
            return mErrorCode;
        }

        String getErrorMsg() {
            waitForCallback();
            assertThat(mSuccess).isFalse();
            return mErrorMsg;
        }
    }
}
