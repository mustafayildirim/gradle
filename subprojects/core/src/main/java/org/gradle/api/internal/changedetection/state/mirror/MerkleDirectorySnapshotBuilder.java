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

package org.gradle.api.internal.changedetection.state.mirror;

import org.gradle.caching.internal.BuildCacheHasher;
import org.gradle.caching.internal.DefaultBuildCacheHasher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

public class MerkleDirectorySnapshotBuilder implements PhysicalSnapshotVisitor {

    private final RelativePathTracker relativePathTracker = new RelativePathTracker();
    private final Deque<List<PhysicalSnapshot>> levelHolder = new ArrayDeque<List<PhysicalSnapshot>>();
    private final Deque<String> directoryAbsolutePaths = new ArrayDeque<String>();
    private PhysicalSnapshot result;

    public boolean preVisitDirectory(String absolutePath, String name) {
        relativePathTracker.enter(name);
        levelHolder.addLast(new ArrayList<PhysicalSnapshot>());
        directoryAbsolutePaths.addLast(absolutePath);
        return true;
    }

    @Override
    public boolean preVisitDirectory(PhysicalDirectorySnapshot directorySnapshot) {
        return preVisitDirectory(directorySnapshot.getAbsolutePath(), directorySnapshot.getName());
    }

    @Override
    public void visit(PhysicalSnapshot fileSnapshot) {
        if (relativePathTracker.isRoot()) {
            result = fileSnapshot;
        } else {
            levelHolder.peekLast().add(fileSnapshot);
        }
    }

    @Override
    public void postVisitDirectory() {
        String name = relativePathTracker.leave();
        List<PhysicalSnapshot> children = levelHolder.removeLast();
        String absolutePath = directoryAbsolutePaths.removeLast();
        Collections.sort(children, PhysicalSnapshot.BY_NAME);
        BuildCacheHasher hasher = new DefaultBuildCacheHasher();
        hasher.putHash(PhysicalDirectorySnapshot.SIGNATURE);
        for (PhysicalSnapshot child : children) {
            hasher.putString(child.getName());
            if (child instanceof PhysicalDirectorySnapshot) {
                hasher.putHash(((PhysicalDirectorySnapshot) child).getTreeHash());
            } else {
                hasher.putHash(child.getContentHash());
            }
        }
        ImmutablePhysicalDirectorySnapshot directorySnapshot = new ImmutablePhysicalDirectorySnapshot(absolutePath, name, children, hasher.hash());
        List<PhysicalSnapshot> siblings = levelHolder.peekLast();
        if (siblings != null) {
            siblings.add(directorySnapshot);
        } else {
            result = directorySnapshot;
        }
    }

    public boolean isRoot() {
        return relativePathTracker.isRoot();
    }

    public RelativePathTracker getRelativePathTracker() {
        return relativePathTracker;
    }

    public PhysicalSnapshot getResult() {
        return result;
    }
}
