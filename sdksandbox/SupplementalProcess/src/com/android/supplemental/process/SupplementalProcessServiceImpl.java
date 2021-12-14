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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Preconditions;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import dalvik.system.DexClassLoader;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

/** Implementation of Supplemental Process Service. */
public class SupplementalProcessServiceImpl extends Service {

    private static final String TAG = "SupplementalProcess";
    private Injector mInjector;

    // The options below may be passed in a {@code Bundle} while loading or rendering.
    // TODO(b/210670819): Encapsulate in a parcelable.
    public static final String CODE_PROVIDER_KEY = "code-provider-class";
    public static final String WIDTH_KEY = "width";
    public static final String HEIGHT_KEY = "height";


    @GuardedBy("mHeldCode")
    private final Map<IBinder, CodeHolder> mHeldCode = new ArrayMap<>();

    static class Injector {

        private final Context mContext;

        Injector(Context context) {
            mContext = context;
        }

        int getCallingUid() {
            return Binder.getCallingUidOrThrow();
        }

        Context getContext() {
            return mContext;
        }
    }

    public SupplementalProcessServiceImpl() {
    }

    @VisibleForTesting
    SupplementalProcessServiceImpl(Injector injector) {
        mInjector = injector;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        mBinder = new SupplementalProcessServiceDelegate();
        mInjector = new Injector(getApplicationContext());
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mInjector.getContext().enforceCallingPermission(android.Manifest.permission.DUMP,
                "Can't dump " + TAG);
        synchronized (mHeldCode) {
            // TODO(b/211575098): Use IndentingPrintWriter for better formatting
            if (mHeldCode.isEmpty()) {
                writer.println("mHeldCode is empty");
            } else {
                writer.print("mHeldCode size: ");
                writer.println(mHeldCode.size());
                for (CodeHolder codeHolder : mHeldCode.values()) {
                    codeHolder.dump(writer);
                    writer.println();
                }
            }
        }
    }

    private ISupplementalProcessService.Stub mBinder;

    private void enforceCallerIsSystemServer() {
        if (mInjector.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException(
                    "Only system_server is allowed to call this API, actual calling uid is "
                            + mInjector.getCallingUid());
        }
        Binder.clearCallingIdentity();
    }

    private void sendLoadError(ISupplementalProcessToSupplementalProcessManagerCallback callback,
            int errorCode, String message) {
        try {
            callback.onLoadCodeError(errorCode, message);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not send onLoadCodeError");
        }
    }

    private ClassLoader getClassLoader(ApplicationInfo appInfo) {
        return new DexClassLoader(appInfo.sourceDir, null, null, getClass().getClassLoader());
    }

    private void loadCodeInternal(@NonNull IBinder codeToken,
            @NonNull ApplicationInfo applicationInfo,
            @Nullable String codeProviderClassName,
            @NonNull Bundle params,
            @NonNull ISupplementalProcessToSupplementalProcessManagerCallback callback) {
        if (params.containsKey(CODE_PROVIDER_KEY)) {
            codeProviderClassName = params.getString(CODE_PROVIDER_KEY);
        }
        Preconditions.checkStringNotEmpty(codeProviderClassName);
        synchronized (mHeldCode) {
            if (mHeldCode.containsKey(codeToken)) {
                sendLoadError(callback,
                        ISupplementalProcessToSupplementalProcessManagerCallback
                                .LOAD_CODE_ALREADY_LOADED,
                        "Already loaded code for package " + applicationInfo.packageName);
                return;
            }
        }

        try {
            ClassLoader loader = getClassLoader(applicationInfo);
            Class<?> clz = Class.forName(CodeHolder.class.getName(), true, loader);
            CodeHolder codeHolder = (CodeHolder) clz.getDeclaredConstructor().newInstance();
            codeHolder.init(
                    mInjector.getContext(),
                    params,
                    callback,
                    codeProviderClassName,
                    loader);
            synchronized (mHeldCode) {
                mHeldCode.put(codeToken, codeHolder);
            }
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            sendLoadError(callback,
                    ISupplementalProcessToSupplementalProcessManagerCallback.LOAD_CODE_NOT_FOUND,
                    "Failed to find: " + CodeHolder.class.getName());
        } catch (InstantiationException  | IllegalAccessException | InvocationTargetException e) {
            sendLoadError(callback,
                    ISupplementalProcessToSupplementalProcessManagerCallback
                            .LOAD_CODE_INSTANTIATION_ERROR,
                    "Failed to instantiate " + CodeHolder.class.getName() + ": " + e);
        }
    }

    void loadCode(IBinder codeToken, ApplicationInfo applicationInfo, String codeProviderClassName,
            Bundle params, ISupplementalProcessToSupplementalProcessManagerCallback callback) {
        enforceCallerIsSystemServer();
        loadCodeInternal(codeToken, applicationInfo, codeProviderClassName, params, callback);
    }

    final class SupplementalProcessServiceDelegate extends ISupplementalProcessService.Stub {

        @Override
        public void loadCode(
                @NonNull IBinder codeToken,
                @NonNull ApplicationInfo applicationInfo,
                @Nullable String codeProviderClassName,
                @NonNull Bundle params,
                @NonNull ISupplementalProcessToSupplementalProcessManagerCallback callback) {
            Preconditions.checkNotNull(codeToken, "codeToken should not be null");
            Preconditions.checkNotNull(applicationInfo, "applicationInfo should not be null");
            Preconditions.checkNotNull(params, "params should not be null");
            Preconditions.checkNotNull(callback, "callback should not be null");
            SupplementalProcessServiceImpl.this.loadCode(
                    codeToken, applicationInfo, codeProviderClassName, params, callback);
        }
    }
}
