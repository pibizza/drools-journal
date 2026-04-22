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
package org.drools.journal.api;

/**
 * SPI for controlling when the journal rolls to a new page.
 *
 * <p>Called by the journal core after every successful append. The core
 * enforces one unconditional override: a {@link SafepointRecord} always causes
 * a roll regardless of what this strategy returns. Implementations therefore
 * do not need to handle safepoints.
 *
 * <p>Built-in implementations are available via {@link PageRollStrategies}.
 */
@FunctionalInterface
public interface PageRollStrategy {

    /**
     * Decides whether to roll the current page after the most recent append.
     *
     * @param context state of the current page after the append
     * @return {@link RollDecision#ROLL} to seal the current page and start a
     *         new one, or {@link RollDecision#CONTINUE} to keep appending
     */
    RollDecision decide(PageContext context);
}
