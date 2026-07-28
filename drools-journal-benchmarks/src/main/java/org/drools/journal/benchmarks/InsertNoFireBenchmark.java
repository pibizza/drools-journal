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

import org.drools.journal.benchmarks.model.A;
import org.drools.journal.benchmarks.providers.RulesWithJoinsProvider;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.Warmup;

@Warmup(iterations = 5)
@Measurement(iterations = 10)
public class InsertNoFireBenchmark extends AbstractSessionBenchmark {

    @Param({"48", "192"})
    private int rulesNr;

    @Setup(Level.Trial)
    public void setupKieBase() {
        String drl = new RulesWithJoinsProvider(1, false, true).getDrl(rulesNr);
        buildKieBase(drl);
    }

    @Setup(Level.Iteration)
    public void insertRootFact() {
        kieSession.insert(new A(rulesNr + 1));
    }

    @Benchmark
    public int test() {
        return kieSession.fireAllRules();
    }
}
