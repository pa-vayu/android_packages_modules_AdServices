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

import android.os.IBinder;

import com.android.supplemental.process.ISupplementalProcessService;

/**
 * Interface to get hold of SupplementalProcessService
 */
public interface SupplementalProcessServiceProvider {
    /**
     * Initiate a connection with SupplementalProcessService and register the app using the service
     * through {@code appBinder}
     *
     * <p>Return SupplementalProcessService connected for {@code callingUid} or null on error.
     */
    ISupplementalProcessService bindService(int callingUid, IBinder appBinder);
    /**
     * Check if service is connected
     */
    boolean isServiceBound(int callingUid);
}
