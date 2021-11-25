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


import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.supplemental.process.ISupplementalProcessService;

class SupplementalProcessServiceProviderImpl
        implements SupplementalProcessServiceProvider {

    private static final String TAG = "SupplementalProcessServiceProviderImpl";

    // TODO(b/204991850): Pass value using dependency injection to override in tests
    private static final String SUPPLEMENTAL_PROCESS_SERVICE_PACKAGE =
            "com.android.supplemental.process";

    // TODO(b/204991850): Pass value using dependency injection to override in tests
    private static final String SERVICE_INTERFACE =
            "com.android.supplemental.process.SupplementalProcessService";

    private final Object mLock = new Object();

    private final Context mContext;

    private static class SupplementalProcessConnection {
        public ServiceConnection serviceConnection = null;
        public ISupplementalProcessService supplementalProcessService = null;

        boolean isConnected() {
            return (serviceConnection != null && supplementalProcessService != null);
        }
    }

    @GuardedBy("mLock")
    private ArrayMap<UserHandle, SupplementalProcessConnection>
            mUserSupplementalProcessConnections = new ArrayMap<>();


    SupplementalProcessServiceProviderImpl(Context context) {
        mContext = context;
    }

    @Override
    public void bindService(UserHandle callingUser) {
        synchronized (mLock) {
            Log.i(TAG, "Binding to supplemental process for " + callingUser.toString());
            if (isServiceBound(callingUser)) {
                Log.i(TAG, "Supplemental process is already bound");
                return;
            }

            SupplementalProcessConnection userConnection = new SupplementalProcessConnection();
            userConnection.serviceConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    userConnection.supplementalProcessService =
                            ISupplementalProcessService.Stub.asInterface(service);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    // Supplemental process crashed or was killed, system will start it again.
                    // TODO(b/204991850): Handle restarts differently
                    //  (e.g. Exponential backoff retry strategy)
                    userConnection.supplementalProcessService = null;
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        unbindService(callingUser);
                        bindService(callingUser);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            };

            final Intent intent = new Intent(SERVICE_INTERFACE);
            intent.setPackage(SUPPLEMENTAL_PROCESS_SERVICE_PACKAGE);

            boolean bound = mContext.bindServiceAsUser(intent, userConnection.serviceConnection,
                    Context.BIND_AUTO_CREATE, callingUser);

            if (!bound) {
                Log.e(TAG, "Could not find supplemental process service.");
                //TODO(b/204991850): throw exception?
                return;
            }

            mUserSupplementalProcessConnections.put(callingUser, userConnection);
            Log.i(TAG, "Supplemental process has been bound");
        }
    }

    @Override
    public ISupplementalProcessService getService(UserHandle callingUser) {
        synchronized (mLock) {
            return mUserSupplementalProcessConnections.get(callingUser).supplementalProcessService;
        }
    }

    @Override
    public boolean isServiceBound(UserHandle callingUser) {
        synchronized (mLock) {
            return (mUserSupplementalProcessConnections.containsKey(callingUser));
        }
    }

    // TODO(b/204991850): Call when the last app using supplemental process dies
    @Override
    public void unbindService(UserHandle callingUser) {
        synchronized (mLock) {
            Log.i(TAG, "Unbinding from supplemental process");
            if (isServiceBound(callingUser)) {
                SupplementalProcessConnection userConnection =
                        mUserSupplementalProcessConnections.get(callingUser);
                mContext.unbindService(userConnection.serviceConnection);
                mUserSupplementalProcessConnections.remove(callingUser);
            }
            Log.i(TAG, "Supplemental process has been unbound");
        }
    }

}
