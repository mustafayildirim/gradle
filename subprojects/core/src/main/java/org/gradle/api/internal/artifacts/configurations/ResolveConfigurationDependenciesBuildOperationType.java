/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.api.internal.artifacts.configurations;

import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.internal.operations.BuildOperationType;
import org.gradle.internal.scan.UsedByScanPlugin;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Resolution of a configuration's dependencies.
 *
 * @since 4.4
 */
public final class ResolveConfigurationDependenciesBuildOperationType implements BuildOperationType<ResolveConfigurationDependenciesBuildOperationType.Details, ResolveConfigurationDependenciesBuildOperationType.Result> {

    @UsedByScanPlugin
    public interface Details {

        String getConfigurationName();

        @Nullable
        String getProjectPath();

        boolean isScriptConfiguration();

        @Nullable
        String getConfigurationDescription();

        String getBuildPath();

        boolean isConfigurationVisible();

        boolean isConfigurationTransitive();

        /**
         * @since 4.10
         */
        @Nullable
        List<Repository> getRepositories();

    }

    @UsedByScanPlugin
    public interface Result {

        ResolvedComponentResult getRootComponent();

    }

    /**
     * A full representation of a repository, with a map of properties that characterize it, such as artifact patterns or whether credentials were set.
     */
    @UsedByScanPlugin
    public interface Repository {

        /**
         *  A unique identifier for this repository _within a single repository container_.
         */
        String getId();

        /**
         * Type of the repository. Taken from the name() of RepositoryDetails.RepositoryType.
         */
        String getType();

        /**
         *  The name of this repository.
         */
        String getName();

        /**
         * Map of properties characterizing this repository.
         * Key is the name() of RepositoryDetails.RepositoryPropertyType.
         * Value can be anything, but simple types such as String or List<String> should be preferred.
         */
        Map<String, ?> getProperties();

    }

}
