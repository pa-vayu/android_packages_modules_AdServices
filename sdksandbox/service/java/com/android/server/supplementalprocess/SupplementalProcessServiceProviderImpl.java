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

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.TransactionTooLargeException;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalManagerRegistry;
import com.android.server.am.ActivityManagerLocal;
import com.android.supplemental.process.ISupplementalProcessService;

import java.io.PrintWriter;
import java.util.Objects;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Implementation of {@link SupplementalProcessServiceProvider}.
 *
 * @hide
 */
@ThreadSafe
class SupplementalProcessServiceProviderImpl implements SupplementalProcessServiceProvider {

    private static final String TAG = "SupplementalProcessServiceProviderImpl";

    private final Object mLock = new Object();

    private final Context mContext;
    private final Injector mInjector;
    private final ActivityManagerLocal mActivityManagerLocal;

    @GuardedBy("mLock")
    private final SparseArray<SupplementalProcessConnection> mAppSupplementalProcessConnections =
            new SparseArray<>();

    SupplementalProcessServiceProviderImpl(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    SupplementalProcessServiceProviderImpl(Context context, Injector injector) {
        mContext = context;
        mInjector = injector;
        mActivityManagerLocal = LocalManagerRegistry.getManager(ActivityManagerLocal.class);
    }

    // TODO(b/214240264): Write E2E tests for checking binding from different apps
    @Override
    @Nullable
    public void bindService(int appUid, ServiceConnection serviceConnection) {
        synchronized (mLock) {
            if (getBoundServiceForApp(appUid) != null) {
                Log.i(TAG, "Supplemental process for " + appUid + " is already bound");
                return;
            }

            Log.i(TAG, "Binding supplemental process for " + appUid);

            ComponentName componentName = getServiceComponentName();
            if (componentName == null) {
                Log.e(TAG, "Failed to find supplemental process service");
                notifyFailedBinding(serviceConnection);
                return;
            }
            final Intent intent = new Intent().setComponent(componentName);

            SupplementalProcessConnection supplementalProcessConnection =
                    new SupplementalProcessConnection(serviceConnection);

            try {
                boolean bound = mActivityManagerLocal.startAndBindSupplementalProcessService(intent,
                        serviceConnection, appUid);
                if (!bound) {
                    mContext.unbindService(serviceConnection);
                    notifyFailedBinding(serviceConnection);
                    return;
                }
            } catch (TransactionTooLargeException e) {
                Log.e(TAG, "Transaction too large, could not bind to supplemental process");
                notifyFailedBinding(serviceConnection);
                return;
            }

            mAppSupplementalProcessConnections.append(appUid, supplementalProcessConnection);
            Log.i(TAG, "Supplemental process has been bound");
        }
    }

    // a way to notify manager that binding never happened
    private void notifyFailedBinding(ServiceConnection serviceConnection) {
        serviceConnection.onNullBinding(null);
    }

    @Override
    public void dump(PrintWriter writer) {
        synchronized (mLock) {
            if (mAppSupplementalProcessConnections.size() == 0) {
                writer.println("mAppSupplementalProcessConnections is empty");
            } else {
                writer.print("mAppSupplementalProcessConnections size: ");
                writer.println(mAppSupplementalProcessConnections.size());
                for (int i = 0; i < mAppSupplementalProcessConnections.size(); i++) {
                    writer.printf("Supplemental process for UID: %s, isConnected: %s",
                            mAppSupplementalProcessConnections.keyAt(i),
                            mAppSupplementalProcessConnections.valueAt(i).isConnected());
                    writer.println();
                }
            }
        }
    }

    @Override
    public void unbindService(int appUid) {
        synchronized (mLock) {
            SupplementalProcessConnection supplementalProcess =
                    getSupplementalProcessConnectionLocked(appUid);

            if (supplementalProcess == null) {
                // Skip, already unbound
                return;
            }

            mContext.unbindService(supplementalProcess.getServiceConnection());
            mAppSupplementalProcessConnections.delete(appUid);
            Log.i(TAG, "Supplemental process has been unbound");
        }
    }

    @Override
    @Nullable
    public ISupplementalProcessService getBoundServiceForApp(int appUid) {
        synchronized (mLock) {
            if (mAppSupplementalProcessConnections.contains(appUid)) {
                return Objects.requireNonNull(mAppSupplementalProcessConnections.get(appUid))
                        .getSupplementalProcessService();
            }
        }
        return null;
    }

    @Override
    public void setBoundServiceForApp(int appUid, ISupplementalProcessService service) {
        synchronized (mLock) {
            if (mAppSupplementalProcessConnections.contains(appUid)) {
                Objects.requireNonNull(mAppSupplementalProcessConnections.get(appUid))
                        .setSupplementalProcessService(service);
            }
        }
    }

    @Nullable
    private ComponentName getServiceComponentName() {
        final Intent intent = new Intent(mInjector.getServiceClass());
        intent.setPackage(mInjector.getServicePackage());

        final ResolveInfo resolveInfo = mContext.getPackageManager().resolveService(intent,
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
        if (resolveInfo == null) {
            Log.e(TAG, "Failed to find resolveInfo for supplemental process service");
            return null;
        }

        final ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        if (serviceInfo == null) {
            Log.e(TAG, "Failed to find serviceInfo for supplemental process service");
            return null;
        }

        return new ComponentName(serviceInfo.packageName, serviceInfo.name);
    }

    @GuardedBy("mLock")
    @Nullable
    private SupplementalProcessConnection getSupplementalProcessConnectionLocked(int appUid) {
        return mAppSupplementalProcessConnections.get(appUid);
    }

    private static class SupplementalProcessConnection {
        private final ServiceConnection mServiceConnection;
        @Nullable
        private ISupplementalProcessService mSupplementalProcessService = null;

        SupplementalProcessConnection(ServiceConnection serviceConnection) {
            mServiceConnection = serviceConnection;
        }

        @Nullable
        public ISupplementalProcessService getSupplementalProcessService() {
            return mSupplementalProcessService;
        }

        public ServiceConnection getServiceConnection() {
            return mServiceConnection;
        }

        public void setSupplementalProcessService(ISupplementalProcessService service) {
            mSupplementalProcessService = service;
        }

        boolean isConnected() {
            return mSupplementalProcessService != null;
        }
    }

    @VisibleForTesting
    static class Injector {
        public String getServicePackage() {
            return "com.android.supplemental.process";
        }

        public String getServiceClass() {
            return "com.android.supplemental.process.SupplementalProcessService";
        }
    }
}
