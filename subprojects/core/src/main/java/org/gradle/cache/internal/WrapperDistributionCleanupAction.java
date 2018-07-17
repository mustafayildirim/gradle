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

package org.gradle.cache.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.Result;
import org.gradle.util.GradleVersion;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.apache.commons.io.filefilter.FileFilterUtils.directoryFileFilter;
import static org.gradle.internal.Result.error;
import static org.gradle.internal.Result.value;
import static org.gradle.util.CollectionUtils.single;

public class WrapperDistributionCleanupAction {

    @VisibleForTesting static final String WRAPPER_DISTRIBUTION_FILE_PATH = "wrapper/dists";
    private static final Logger LOGGER = Logging.getLogger(WrapperDistributionCleanupAction.class);
    private static final String BUILD_RECEIPT_ZIP_ENTRY_PATH = StringUtils.removeStart(GradleVersion.RESOURCE_NAME, "/");

    private static final ImmutableMap<String, Pattern> JAR_FILE_PATTERNS_BY_PREFIX;
    static {
        Set<String> prefixes = ImmutableSet.of(
            "gradle-base-services", // 4.x
            "gradle-version-info", // 2.x - 3.x
            "gradle-core" // 1.x
        );
        ImmutableMap.Builder<String, Pattern> builder = ImmutableMap.builder();
        for (String prefix : prefixes) {
            builder.put(prefix, Pattern.compile('^' + Pattern.quote(prefix) + "-\\d.+.jar$"));
        }
        JAR_FILE_PATTERNS_BY_PREFIX = builder.build();
    }

    private final File distsDir;
    private final UsedGradleVersions usedGradleVersions;

    public WrapperDistributionCleanupAction(File gradleUserHomeDirectory, UsedGradleVersions usedGradleVersions) {
        this.distsDir = new File(gradleUserHomeDirectory, WRAPPER_DISTRIBUTION_FILE_PATH);
        this.usedGradleVersions = usedGradleVersions;
    }

    public void execute() {
        Set<GradleVersion> usedVersions = this.usedGradleVersions.getUsedGradleVersions();
        Multimap<GradleVersion, File> checksumDirsByVersion = determineChecksumDirsByVersion();
        for (GradleVersion version : checksumDirsByVersion.keySet()) {
            if (!usedVersions.contains(version) && version.compareTo(GradleVersion.current()) < 0) {
                deleteDistributions(checksumDirsByVersion.get(version));
            }
        }
    }

    private void deleteDistributions(Collection<File> dirs) {
        Set<File> parentsOfDeletedDistributions = Sets.newLinkedHashSet();
        for (File checksumDir : dirs) {
            LOGGER.debug("Deleting distribution at {}", checksumDir);
            if (FileUtils.deleteQuietly(checksumDir)) {
                parentsOfDeletedDistributions.add(checksumDir.getParentFile());
            }
        }
        for (File parentDir : parentsOfDeletedDistributions) {
            if (listFiles(parentDir).isEmpty()) {
                parentDir.delete();
            }
        }
    }

    private Multimap<GradleVersion, File> determineChecksumDirsByVersion() {
        Multimap<GradleVersion, File> checksumDirsByVersion = ArrayListMultimap.create();
        for (File dir : listDirs(distsDir)) {
            for (File checksumDir : listDirs(dir)) {
                Result<GradleVersion> result = determineGradleVersionFromBuildReceipt(checksumDir);
                if (result.hasValue()) {
                    checksumDirsByVersion.put(result.getValue(), checksumDir);
                } else {
                    LOGGER.debug("Could not determine Gradle version for {}: {}", checksumDir, result.getError());
                }
            }
        }
        return checksumDirsByVersion;
    }

    private Result<GradleVersion> determineGradleVersionFromBuildReceipt(File checksumDir) {
        List<File> subDirs = listDirs(checksumDir);
        if (subDirs.size() != 1) {
            return error("A Gradle distribution must contain exactly one subdirectory: {0}", subDirs);
        }
        return determineGradleVersionFromDistribution(single(subDirs));
    }

    @VisibleForTesting
    protected Result<GradleVersion> determineGradleVersionFromDistribution(File distributionHomeDir) {
        Map<File, String> checkedJarFiles = new LinkedHashMap<File, String>();
        for (Map.Entry<String, Pattern> entry : JAR_FILE_PATTERNS_BY_PREFIX.entrySet()) {
            List<File> jarFiles = listFiles(new File(distributionHomeDir, "lib"), new RegexFileFilter(entry.getValue()));
            if (!jarFiles.isEmpty()) {
                if (jarFiles.size() > 1) {
                    return error("A Gradle distribution must contain at most one {0}-*.jar: {1}", entry.getKey(), jarFiles);
                }
                File jarFile = single(jarFiles);
                Result<GradleVersion> result = readGradleVersionFromJarFile(jarFile);
                if (result.hasValue()) {
                    return result;
                }
                checkedJarFiles.put(jarFile, result.getError());
            }
        }
        return error("No checked JAR file contained a build receipt: {0}", checkedJarFiles);
    }

    private Result<GradleVersion> readGradleVersionFromJarFile(File jarFile) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(jarFile);
            return readGradleVersionFromBuildReceipt(zipFile);
        } catch (IOException e) {
            return error("Could not open {0}: {1}: {2}", jarFile, e.getClass().getName(), e.getMessage());
        } finally {
            IOUtils.closeQuietly(zipFile);
        }
    }

    private Result<GradleVersion> readGradleVersionFromBuildReceipt(ZipFile zipFile) {
        ZipEntry zipEntry = zipFile.getEntry(BUILD_RECEIPT_ZIP_ENTRY_PATH);
        if (zipEntry == null) {
            return error("JAR does not contain " + BUILD_RECEIPT_ZIP_ENTRY_PATH + ": {0}", zipFile.getName());
        }
        InputStream in = null;
        try {
            in = zipFile.getInputStream(zipEntry);
            Properties properties = new Properties();
            properties.load(in);
            String versionString = properties.getProperty(GradleVersion.VERSION_NUMBER_PROPERTY);
            return value(GradleVersion.version(versionString));
        } catch (Exception e) {
            return error("Failed to read version from entry {0}: {1}: {2}", zipEntry, e.getClass().getName(), e.getMessage());
        } finally {
            IOUtils.closeQuietly(in);
        }
    }

    private List<File> listDirs(File baseDir) {
        return listFiles(baseDir, directoryFileFilter());
    }

    private List<File> listFiles(File baseDir) {
        return listFiles(baseDir, null);
    }

    private List<File> listFiles(File baseDir, FileFilter filter) {
        File[] dirs = baseDir.listFiles(filter);
        return dirs == null ? Collections.<File>emptyList() : Arrays.asList(dirs);
    }

}
