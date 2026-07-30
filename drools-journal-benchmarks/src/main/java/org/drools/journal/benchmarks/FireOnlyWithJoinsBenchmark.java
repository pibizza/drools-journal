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
import org.drools.journal.benchmarks.model.B;
import org.drools.journal.benchmarks.model.C;
import org.drools.journal.benchmarks.providers.RulesWithJoinsProvider;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.Warmup;

@Warmup(iterations = 2000)
@Measurement(iterations = 1000)
public class FireOnlyWithJoinsBenchmark extends AbstractSessionBenchmark {

    @Param({"32"})
    private int rulesNr;

    @Param({"10", "15"})
    private int factsNr;

    @Param({"1", "2"})
    private int joinsNr;

    @Setup(Level.Trial)
    public void setupKieBase() {
        String drl = new RulesWithJoinsProvider(joinsNr, false, true).getDrl(rulesNr);
        buildKieBase(drl);
    }

    @Setup(Level.Iteration)
    public void insertFacts() {
        kieSession.insert(new A(rulesNr + 1));
        for (int i = 0; i < factsNr; i++) {
            kieSession.insert(new B(rulesNr + i + 3));
            if (joinsNr > 1) {
                kieSession.insert(new C(rulesNr + i + 3));
            }
        }
    }

    @Benchmark
    public int test() {
        return kieSession.fireAllRules();
    }
}
