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
package org.drools.journal.chronicle.internal;

import java.util.ArrayList;
import java.util.List;

import net.openhft.chronicle.bytes.MethodReader;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;

public final class CatalogIndex implements ChronicleCatalogWriteOps {

    private final List<String> pages = new ArrayList<>();
    private int highestPageCounter;

    @Override
    public void pageCreated(final int pageId) {
        pages.add(String.valueOf(pageId));
        if (pageId > highestPageCounter) {
            highestPageCounter = pageId;
        }
    }

    @Override
    public void compactionPrepare(final String preparingPageId, final String... replacedPageIds) {
    }

    @Override
    public void compactionCommit(final String mergedPageId, final String... replacedPageIds) {
        List<String> spliced = spliceIntoIndex(pages, mergedPageId, replacedPageIds);
        pages.clear();
        pages.addAll(spliced);
    }

    public List<String> livePages() {
        return List.copyOf(pages);
    }

    public int highestPageCounter() {
        return highestPageCounter;
    }

    public static CatalogIndex build(final SingleChronicleQueue catalogQueue) {
        CatalogIndex index = new CatalogIndex();
        ExcerptTailer tailer = catalogQueue.createTailer();
        tailer.toStart();
        MethodReader reader = tailer.methodReader(index);
        while (reader.readOne()) {
            // entries processed in-place via the handler methods above
        }
        return index;
    }

    static List<String> spliceIntoIndex(final List<String> pageIndex, final String mergedId, final String... replacedIds) {
        if (replacedIds.length == 0) {
            return new ArrayList<>(pageIndex);
        }
        List<String> result = new ArrayList<>(pageIndex);
        int r = replacedIds.length - 1;
        for (int i = result.size() - 1; i >= 0 && r >= 0; i--) {
            if (result.get(i).equals(replacedIds[r])) {
                if (r == 0) {
                    result.set(i, mergedId);
                } else {
                    result.remove(i);
                }
                r--;
            }
        }
        return result;
    }
}
