/*
 * Copyright (c) 2026 Drools Journal Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.drools.journal.core;

import org.drools.core.common.IdentityObjectStore;
import org.drools.core.common.InternalFactHandle;

public class JournalledObjectStore extends IdentityObjectStore {

    @Override
    public void updateHandle(final InternalFactHandle handle, final Object object) {
        // Bypass the listener's objectInserted/objectDeleted events — an update is not a
        // retract followed by an insert. The objectUpdated event fired by NamedEntryPoint
        // writes the InsertRecord snapshot via JournallingRuntimeEventListener.
        super.removeHandle(handle);
        handle.setObject(object);
        super.addHandle(handle, object);
    }
}
