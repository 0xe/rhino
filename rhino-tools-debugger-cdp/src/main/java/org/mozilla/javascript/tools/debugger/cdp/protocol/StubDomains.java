/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.tools.debugger.cdp.transport.CdpTransport;

/**
 * Registers minimal stubs for domains that DevTools probes on connect but that a JS-only runtime
 * does not implement. Every method replies {@code {}} so the frontend never sees an error during
 * capability probing.
 */
public final class StubDomains {

    private StubDomains() {}

    public static void registerAll(CdpDispatcher dispatcher) {
        dispatcher.register("Profiler", new OkDomain());
        dispatcher.register("HeapProfiler", new OkDomain());
        dispatcher.register("Schema", new SchemaDomain());
        dispatcher.register("Network", new OkDomain());
        dispatcher.register("Log", new OkDomain());
        dispatcher.register("Inspector", new InspectorDomain());
        dispatcher.register("Target", new OkDomain());
    }

    private static final class OkDomain implements DomainHandler {
        @Override
        public void handle(String command, Scriptable params, CdpTransport.Replier replier) {
            replier.ok(Collections.emptyMap());
        }
    }

    private static final class SchemaDomain implements DomainHandler {
        @Override
        public void handle(String command, Scriptable params, CdpTransport.Replier replier) {
            if ("getDomains".equals(command)) {
                List<Map<String, Object>> list = new ArrayList<>();
                addDomain(list, "Debugger", "1.3");
                addDomain(list, "Runtime", "1.3");
                addDomain(list, "Profiler", "1.3");
                addDomain(list, "HeapProfiler", "1.3");
                addDomain(list, "Schema", "1.3");
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("domains", list);
                replier.ok(out);
            } else {
                replier.ok(Collections.emptyMap());
            }
        }

        private static void addDomain(List<Map<String, Object>> list, String name, String version) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", name);
            d.put("version", version);
            list.add(d);
        }
    }

    private static final class InspectorDomain implements DomainHandler {
        @Override
        public void handle(String command, Scriptable params, CdpTransport.Replier replier) {
            replier.ok(Collections.emptyMap());
        }
    }
}
