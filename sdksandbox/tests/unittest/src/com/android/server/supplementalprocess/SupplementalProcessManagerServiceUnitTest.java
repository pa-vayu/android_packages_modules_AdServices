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

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;



import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.supplementalprocess.IRemoteCodeCallback;
import android.supplementalprocess.SupplementalProcessManager;
import android.util.ArrayMap;
import android.view.SurfaceControlViewHost;

import androidx.test.InstrumentationRegistry;

import com.android.supplemental.process.ISupplementalProcessManagerToSupplementalProcessCallback;
import com.android.supplemental.process.ISupplementalProcessService;
import com.android.supplemental.process.ISupplementalProcessToSupplementalProcessManagerCallback;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link SupplementalProcessManagerService}.
 */
public class SupplementalProcessManagerServiceUnitTest {

    private SupplementalProcessManagerService mService;
    private FakeSupplementalProcessService mSupplementalProcessService;

    private static final String CODE_PROVIDER_PACKAGE = "com.android.codeprovider";

    @Before
    public void setup() {
        Context context = InstrumentationRegistry.getContext();
        mSupplementalProcessService = new FakeSupplementalProcessService();
        SupplementalProcessServiceProvider provider =
                new FakeSupplementalProcessProvider(mSupplementalProcessService);
        mService = new SupplementalProcessManagerService(context, provider);
    }

    @Test
    public void testLoadCodeIsSuccessful() throws Exception {
        FakeInitCodeCallback callback = new FakeInitCodeCallback();
        mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
        // Assume SupplementalProcess loads successfully
        mSupplementalProcessService.sendLoadCodeSuccessful();
        assertThat(callback.isLoadCodeSuccessful()).isTrue();
    }

    @Test
    public void testLoadCodePackageDoesNotExist() throws Exception {
        FakeInitCodeCallback callback = new FakeInitCodeCallback();
        mService.loadCode("does.not.exist", "1", new Bundle(), callback);

        // Verify loading failed
        assertThat(callback.isLoadCodeSuccessful()).isFalse();
        assertThat(callback.getLoadCodeErrorCode())
                .isEqualTo(SupplementalProcessManager.LOAD_CODE_NOT_FOUND);
        assertThat(callback.getLoadCodeErrorMsg()).contains("not found for loading");
    }

    @Test
    public void testLoadCode_errorFromSupplementalProcess() throws Exception {
        FakeInitCodeCallback callback = new FakeInitCodeCallback();
        mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
        mSupplementalProcessService.sendLoadCodeError();

        // Verify loading failed
        assertThat(callback.isLoadCodeSuccessful()).isFalse();
        assertThat(callback.getLoadCodeErrorCode()).isEqualTo(
                SupplementalProcessManager.LOAD_CODE_INTERNAL_ERROR);
    }

    @Test
    public void testLoadCode_successOnFirstLoad_errorOnLoadAgain() throws Exception {
        // Load it once
        {
            FakeInitCodeCallback callback = new FakeInitCodeCallback();
            mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
            // Assume SupplementalProcess loads successfully
            mSupplementalProcessService.sendLoadCodeSuccessful();
            assertThat(callback.isLoadCodeSuccessful()).isTrue();
        }

        // Load it again
        {
            FakeInitCodeCallback callback = new FakeInitCodeCallback();
            mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
            // Verify loading failed
            assertThat(callback.isLoadCodeSuccessful()).isFalse();
            assertThat(callback.getLoadCodeErrorCode()).isEqualTo(
                    SupplementalProcessManager.LOAD_CODE_ALREADY_LOADED);
            assertThat(callback.getLoadCodeErrorMsg()).contains("has been loaded already");
        }
    }

    @Test
    public void testLoadCode_errorOnFirstLoad_canBeLoadedAgain() throws Exception {
        // Load code, but make it fail
        {
            FakeInitCodeCallback callback = new FakeInitCodeCallback();
            mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
            // Assume SupplementalProcess load fails
            mSupplementalProcessService.sendLoadCodeError();
            assertThat(callback.isLoadCodeSuccessful()).isFalse();
        }

        // Caller should be able to retry loading the code
        {
            FakeInitCodeCallback callback = new FakeInitCodeCallback();
            mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
            // Assume SupplementalProcess loads successfully
            mSupplementalProcessService.sendLoadCodeSuccessful();
            assertThat(callback.isLoadCodeSuccessful()).isTrue();
        }
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
        mSupplementalProcessService.sendLoadCodeSuccessful();
        assertThat(callback.isLoadCodeSuccessful()).isTrue();

        // Verify codeToken is not null
        IBinder codeToken = callback.getCodeToken();
        assertThat(codeToken).isNotNull();

        // 2. Call request package with the retrieved codeToken
        mService.requestSurfacePackage(codeToken, new Binder(), 0, new Bundle());
        mSupplementalProcessService.sendSurfacePackageReady();
        assertThat(callback.isRequestSurfacePackageSuccessful()).isTrue();
    }

    @Test
    public void testRequestSurfacePackageFailedAfterAppDied() throws Exception {
        FakeInitCodeCallback callback = spy(new FakeInitCodeCallback());
        doReturn(mock(Binder.class)).when(callback).asBinder();

        ArgumentCaptor<IBinder.DeathRecipient> deathRecipient = ArgumentCaptor
                .forClass(IBinder.DeathRecipient.class);

        mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
        mSupplementalProcessService.sendLoadCodeSuccessful();
        assertThat(callback.isLoadCodeSuccessful()).isTrue();

        verify(callback.asBinder()).linkToDeath(deathRecipient.capture(), eq(0));

        // App Died
        deathRecipient.getValue().binderDied();

        // After App Died
        SecurityException thrown = assertThrows(
                SecurityException.class,
                () -> mService.requestSurfacePackage(callback.getCodeToken(), new Binder(),
                        0, new Bundle())
        );
        assertThat(thrown).hasMessageThat().contains("codeToken is invalid");
    }

    @Test
    public void testSurfacePackageError() throws Exception {
        FakeInitCodeCallback callback = new FakeInitCodeCallback();
        mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
        // Assume SurfacePackage encounters an error.
        mSupplementalProcessService.sendSurfacePackageError(
                SupplementalProcessManager.SURFACE_PACKAGE_INTERNAL_ERROR, "bad surface");
        assertThat(callback.getSurfacePackageErrorMsg()).contains("bad surface");
        assertThat(callback.getSurfacePackageErrorCode())
                .isEqualTo(SupplementalProcessManager.SURFACE_PACKAGE_INTERNAL_ERROR);
    }

    @Test(expected = SecurityException.class)
    public void testDumpWithoutPermission() {
        mService.dump(new FileDescriptor(), new PrintWriter(new StringWriter()), new String[0]);
    }

    // ManagerToAppCallback
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

        int getLoadCodeErrorCode() {
            waitForLatch(mLoadCodeLatch);
            assertThat(mLoadCodeSuccess).isFalse();
            return mErrorCode;
        }

        String getLoadCodeErrorMsg() {
            waitForLatch(mLoadCodeLatch);
            assertThat(mLoadCodeSuccess).isFalse();
            return mErrorMsg;
        }

        int getSurfacePackageErrorCode() {
            waitForLatch(mSurfacePackageLatch);
            assertThat(mSurfacePackageSuccess).isFalse();
            return mErrorCode;
        }

        String getSurfacePackageErrorMsg() {
            waitForLatch(mSurfacePackageLatch);
            assertThat(mSurfacePackageSuccess).isFalse();
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

    /**
     * Fake service provider that returns local instance of {@link FakeSupplementalProcessService}
     */
    private static class FakeSupplementalProcessProvider
            implements SupplementalProcessServiceProvider {
        private final ISupplementalProcessService mSupplementalProcessService;
        private final ArrayMap<UserHandle, ISupplementalProcessService> mService = new ArrayMap<>();

        FakeSupplementalProcessProvider(ISupplementalProcessService service) {
            mSupplementalProcessService = service;
        }

        @Override
        public ISupplementalProcessService bindService(int callingUid, IBinder appBinder) {
            final UserHandle callingUser = UserHandle.getUserHandleForUid(callingUid);
            if (mService.containsKey(callingUser)) {
                return mService.get(callingUser);
            }

            mService.put(callingUser, mSupplementalProcessService);
            return mService.get(callingUser);
        }

        @Override
        public boolean isServiceBound(int callingUid) {
            final UserHandle callingUser = UserHandle.getUserHandleForUid(callingUid);
            return mService.containsKey(callingUser);
        }
    }

    private static class FakeSupplementalProcessService extends ISupplementalProcessService.Stub {
        private ISupplementalProcessToSupplementalProcessManagerCallback mCodeToManagerCallback;
        private final ISupplementalProcessManagerToSupplementalProcessCallback
                mManagerToCodeCallback;

        boolean mSurfacePackageRequested = false;

        FakeSupplementalProcessService() {
            mManagerToCodeCallback = new FakeManagerToCodeCallback();
        }

        @Override
        public void loadCode(IBinder codeToken, ApplicationInfo info, String codeProviderClassName,
                Bundle params, ISupplementalProcessToSupplementalProcessManagerCallback callback) {
            mCodeToManagerCallback = callback;
        }

        void sendLoadCodeSuccessful() throws RemoteException {
            mCodeToManagerCallback.onLoadCodeSuccess(new Bundle(), mManagerToCodeCallback);
        }

        void sendLoadCodeError() throws RemoteException {
            mCodeToManagerCallback.onLoadCodeError(
                    SupplementalProcessManager.LOAD_CODE_INTERNAL_ERROR, "Internal error");
        }

        void sendSurfacePackageReady() throws RemoteException {
            if (mSurfacePackageRequested) {
                mCodeToManagerCallback.onSurfacePackageReady(
                        /*hostToken=*/null, /*displayId=*/0, /*params=*/null);
            }
        }

        void sendSurfacePackageError(int errorCode, String errorMsg) throws RemoteException {
            mCodeToManagerCallback.onSurfacePackageError(errorCode, errorMsg);
        }

        private class FakeManagerToCodeCallback extends
                ISupplementalProcessManagerToSupplementalProcessCallback.Stub {
            @Override
            public void onSurfacePackageRequested(IBinder hostToken,
                    int displayId, Bundle extraParams) {
                mSurfacePackageRequested = true;
            }
        }

    }
}
