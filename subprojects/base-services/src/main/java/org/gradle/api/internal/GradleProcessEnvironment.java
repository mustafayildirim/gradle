/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal;

import org.gradle.internal.os.OperatingSystem;

import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

public class GradleProcessEnvironment {
    private static final Map<String, String> ENVS;

    static {
        ENVS = new ConcurrentSkipListMap<String, String>(OperatingSystem.current().isWindows() ? NameComparator.INSTANCE : null);
        ENVS.putAll(System.getenv());
    }

    public static Map<String, String> getenv() {
        return Collections.unmodifiableMap(ENVS);
    }

    public static String getenv(String env) {
        return ENVS.get(env);
    }

    public static void unsetenv(String env) {
        ENVS.remove(env);
    }

    public static void setenv(String env, String value) {
        ENVS.put(env, value);
    }

    // This is copied from JDK
    // http://hg.openjdk.java.net/jdk/jdk/file/99a7d10f248c/src/java.base/windows/classes/java/lang/ProcessEnvironment.java#l195
    private enum NameComparator implements Comparator<String> {
        INSTANCE;

        public int compare(String s1, String s2) {
            // We can't use String.compareToIgnoreCase since it
            // canonicalizes to lower case, while Windows
            // canonicalizes to upper case!  For example, "_" should
            // sort *after* "Z", not before.
            int n1 = s1.length();
            int n2 = s2.length();
            int min = Math.min(n1, n2);
            for (int i = 0; i < min; i++) {
                char c1 = s1.charAt(i);
                char c2 = s2.charAt(i);
                if (c1 != c2) {
                    c1 = Character.toUpperCase(c1);
                    c2 = Character.toUpperCase(c2);
                    if (c1 != c2)
                        // No overflow because of numeric promotion
                        return c1 - c2;
                }
            }
            return n1 - n2;
        }
    }
}

