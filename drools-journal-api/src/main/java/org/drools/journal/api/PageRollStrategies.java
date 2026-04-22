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
 * Factory for the built-in {@link PageRollStrategy} implementations.
 */
public final class PageRollStrategies {

    private PageRollStrategies() {
    }

    /**
     * Never rolls on its own — relies entirely on the core's unconditional
     * safepoint-forced roll. Use when page boundaries should coincide exactly
     * with safepoints. This is the default strategy.
     */
    public static PageRollStrategy safepointOnly() {
        return context -> RollDecision.CONTINUE;
    }

    /**
     * Rolls when the current page exceeds {@code maxBytes}.
     *
     * @param maxBytes byte threshold; must be positive
     */
    public static PageRollStrategy sizeThreshold(final long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive, got: " + maxBytes);
        }
        return context -> context.currentPageBytes() >= maxBytes
                ? RollDecision.ROLL
                : RollDecision.CONTINUE;
    }

    /**
     * Rolls every {@code maxRecords} records.
     *
     * @param maxRecords record count threshold; must be positive
     */
    public static PageRollStrategy countThreshold(final long maxRecords) {
        if (maxRecords <= 0) {
            throw new IllegalArgumentException("maxRecords must be positive, got: " + maxRecords);
        }
        return context -> context.currentRecordCount() >= maxRecords
                ? RollDecision.ROLL
                : RollDecision.CONTINUE;
    }

    /**
     * Rolls when <em>any</em> of the given strategies returns
     * {@link RollDecision#ROLL}.
     *
     * @param strategies at least one strategy must be provided
     */
    public static PageRollStrategy composite(final PageRollStrategy... strategies) {
        if (strategies == null || strategies.length == 0) {
            throw new IllegalArgumentException("At least one strategy must be provided");
        }
        return context -> {
            for (final PageRollStrategy strategy : strategies) {
                if (strategy.decide(context) == RollDecision.ROLL) {
                    return RollDecision.ROLL;
                }
            }
            return RollDecision.CONTINUE;
        };
    }
}
