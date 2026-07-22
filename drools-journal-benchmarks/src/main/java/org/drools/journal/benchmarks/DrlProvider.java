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
package org.drools.journal.benchmarks;

import org.drools.journal.api.ModifyLambdaRegistry;
import org.drools.journal.core.JournalDrlPrecompiler;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.internal.utils.KieHelper;

public enum DrlProvider {

    SIMPLE_INSERT("""
            package org.drools.journal.benchmarks
            rule "ProcessInteger"
            when
                $i: Integer()
            then
            end
            """),

    MODIFY_STATUS("""
            package org.drools.journal.benchmarks
            import org.drools.journal.benchmarks.StockItem
            rule "ActivateItem"
            when
                $item : StockItem(status == "pending")
            then
                modify($item) {
                    setStatus("active")
                }
            end
            """);

    private final String drl;

    DrlProvider(final String drl) {
        this.drl = drl;
    }

    public String drl() {
        return drl;
    }

    public KieBase kbase(final ModifyLambdaRegistry registry) {
        String source = drl;
        if (registry != null) {
            source = JournalDrlPrecompiler.rewrite(source, registry,
                    DrlProvider.class.getClassLoader());
        }
        return new KieHelper().addContent(source, ResourceType.DRL).build();
    }
}
