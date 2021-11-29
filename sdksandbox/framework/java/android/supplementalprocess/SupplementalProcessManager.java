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

package android.supplementalprocess;

import android.annotation.SystemService;
import android.content.Context;

/**
 * Supplemental Process Manager.
 * @hide
 */
@SystemService(Context.SUPPLEMENTAL_PROCESS_SERVICE)
public class SupplementalProcessManager {
    private static final String TAG = "SupplementalProcessManager";

    private final ISupplementalProcessManager mService;
    private final Context mContext;

    public SupplementalProcessManager(Context context, ISupplementalProcessManager binder) {
        mContext = context;
        mService = binder;
    }

    /** Error code to represent that there is no such code */
    public static final int LOAD_CODE_NOT_FOUND = 100;

    public static final int LOAD_CODE_INTERNAL_ERROR = 500;
}
