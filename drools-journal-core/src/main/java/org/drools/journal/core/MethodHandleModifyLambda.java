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

import org.drools.journal.api.ModifyLambda;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Arrays;

public class MethodHandleModifyLambda implements ModifyLambda {

    private final MethodHandle[] setters;

    private MethodHandleModifyLambda(final MethodHandle[] setters) {
        this.setters = setters;
    }

    public static MethodHandleModifyLambda forSetters(final Class<?> factType, final String... setterNames) {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle[] handles = new MethodHandle[setterNames.length];
        for (int i = 0; i < setterNames.length; i++) {
            handles[i] = resolveSetter(lookup, factType, setterNames[i]);
        }
        return new MethodHandleModifyLambda(handles);
    }

    @Override
    public void apply(final Object fact, final Object[] params) {
        for (int i = 0; i < setters.length; i++) {
            try {
                setters[i].invoke(fact, params[i]);
            } catch (Throwable e) {
                throw new RuntimeException("Failed to invoke setter at index " + i, e);
            }
        }
    }

    private static MethodHandle resolveSetter(final MethodHandles.Lookup lookup,
                                              final Class<?> factType,
                                              final String setterName) {
        Method method = Arrays.stream(factType.getMethods())
                .filter(m -> m.getName().equals(setterName))
                .filter(m -> m.getParameterCount() == 1)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No single-arg method '" + setterName + "' on " + factType.getName()));
        try {
            return lookup.unreflect(method);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot access " + setterName + " on " + factType.getName(), e);
        }
    }
}
