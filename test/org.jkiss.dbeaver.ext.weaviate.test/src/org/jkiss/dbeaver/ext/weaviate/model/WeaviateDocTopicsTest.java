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

import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WeaviateDocTopicsTest extends DBeaverUnitTest {

    private static final Pattern FOLDER_ID = Pattern.compile("<folder[^>]*\\bid=\"([^\"]+)\"");

    @Test
    public void everyTopicHasAnAbsoluteDocsUrl() {
        for (String id : WeaviateDocTopics.topicIds()) {
            String url = WeaviateDocTopics.urlFor(id);
            Assertions.assertNotNull(url, id + " has no URL");
            Assertions.assertTrue(
                url.startsWith("https://docs.weaviate.io/weaviate/"),
                id + " -> unexpected URL: " + url);
            Assertions.assertFalse(url.endsWith("#"), id + " -> dangling anchor: " + url);
        }
    }

    @Test
    public void unknownFolderHasNoTopic() {
        Assertions.assertNull(WeaviateDocTopics.urlFor("noSuchFolder"));
        Assertions.assertNull(WeaviateDocTopics.urlFor(""));
        Assertions.assertNull(WeaviateDocTopics.urlFor(null));
    }

    /**
     * The folders that carry an explicit {@code id} in plugin.xml are exactly the ones this
     * map documents -- the ids exist for no other reason. Renaming a folder id without
     * updating the map would silently drop its context menu, which is hard to spot by hand.
     */
    @Test
    public void topicIdsMatchTheFoldersDeclaredInPluginXml() throws Exception {
        Set<String> declared = new TreeSet<>();
        try (InputStream is = WeaviateDocTopics.class.getResourceAsStream("/plugin.xml")) {
            Assertions.assertNotNull(is, "plugin.xml not found on the bundle classpath");
            String xml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = FOLDER_ID.matcher(xml);
            while (m.find()) {
                declared.add(m.group(1));
            }
        }
        Assertions.assertEquals(new TreeSet<>(WeaviateDocTopics.topicIds()), declared);
    }
}
