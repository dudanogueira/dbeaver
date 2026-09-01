/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ext.weaviate.model;

import org.jkiss.code.NotNull;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers where replicas were last seen, so a re-read can say what moved.
 * <p>
 * A completed movement changes one thing in the tree: a shard now has a replica somewhere it did
 * not before. That is a single line difference in a list of node names, and it is very easy to
 * miss -- particularly since the refresh that reveals it is the same refresh that redraws
 * everything else.
 * <p>
 * So each read is compared with the one before it and the difference is put in the label. The
 * first sight of a shard reports nothing new: everything would be, and highlighting everything
 * highlights nothing. The marks then clear themselves, because the next read compares against a
 * snapshot that already includes them.
 */
public class WeaviatePlacementTracker {

    private final Map<String, Set<String>> lastSeen = new ConcurrentHashMap<>();

    /**
     * Records where something is now, and reports where it was not before.
     *
     * @param key   what is being tracked, e.g. {@code Orders/abc123}
     * @param nodes where its replicas are now
     * @return the entries that were not there last time; empty on the first look
     */
    @NotNull
    public Set<String> note(@NotNull String key, @NotNull Collection<String> nodes) {
        Set<String> current = new LinkedHashSet<>(nodes);
        Set<String> previous = lastSeen.put(key, current);
        if (previous == null) {
            // Never seen it, so nothing about it is news.
            return Set.of();
        }
        Set<String> added = new LinkedHashSet<>(current);
        added.removeAll(previous);
        return added;
    }

    /** Forgets everything, so the next read is a first look again. */
    public void clear() {
        lastSeen.clear();
    }
}
