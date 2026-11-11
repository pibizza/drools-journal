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

import org.drools.journal.api.DurabilityMode;
import org.drools.journal.api.DurableSessionOption;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurabilityModeOptionTest {

    @Test
    void getDurabilityMode_whenUnset_defaultsToJournal() {
        assertThat(DurableSessionOption.newSession().getDurabilityMode())
                .isEqualTo(DurabilityMode.JOURNAL);
    }

    @Test
    void withMode_snapshot_isReturnedByGetter() {
        DurableSessionOption opt = DurableSessionOption.newSession()
                .withMode(DurabilityMode.SNAPSHOT);
        assertThat(opt.getDurabilityMode()).isEqualTo(DurabilityMode.SNAPSHOT);
    }

    @Test
    void withMode_null_isRejected() {
        assertThatThrownBy(() -> DurableSessionOption.newSession().withMode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
