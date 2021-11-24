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

import android.os.UserHandle;

import com.android.supplemental.process.ISupplementalProcessService;

/**
 * Interface to get hold of SupplementalProcessService
 */
public interface SupplementalProcessServiceProvider {
    /**
     * Initiate a connection with SupplementalProcessService
     */
    void bindService(UserHandle callingUser);
    /**
     * Return SupplementalProcessService connected for
     * {@code callingUser}
     */
    ISupplementalProcessService getService(UserHandle callingUser);
    /**
     * Check if service is connected
     */
    boolean isServiceBound(UserHandle callingUser);
    /**
     * Disconnect from the service
     */
    void unbindService(UserHandle callingUser);
}
