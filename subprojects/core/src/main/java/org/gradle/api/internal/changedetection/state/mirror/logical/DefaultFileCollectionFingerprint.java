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

package org.gradle.api.internal.changedetection.state.mirror.logical;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import org.gradle.api.internal.cache.StringInterner;
import org.gradle.api.internal.changedetection.rules.TaskStateChangeVisitor;
import org.gradle.api.internal.changedetection.state.EmptyFileCollectionSnapshot;
import org.gradle.api.internal.changedetection.state.FileCollectionSnapshot;
import org.gradle.api.internal.changedetection.state.NormalizedFileSnapshot;
import org.gradle.api.internal.changedetection.state.SnapshotMapSerializer;
import org.gradle.api.internal.changedetection.state.mirror.FilteredPhysicalSnapshot;
import org.gradle.api.internal.changedetection.state.mirror.PhysicalDirectorySnapshot;
import org.gradle.api.internal.changedetection.state.mirror.PhysicalSnapshot;
import org.gradle.api.internal.changedetection.state.mirror.PhysicalSnapshotVisitor;
import org.gradle.caching.internal.BuildCacheHasher;
import org.gradle.caching.internal.DefaultBuildCacheHasher;
import org.gradle.internal.MutableBoolean;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;
import org.gradle.internal.serialize.HashCodeSerializer;
import org.gradle.internal.serialize.Serializer;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class DefaultFileCollectionFingerprint implements FileCollectionSnapshot {

    private final Map<String, NormalizedFileSnapshot> snapshots;
    private final FingerprintCompareStrategy strategy;
    private final Iterable<PhysicalSnapshot> roots;
    private Multimap<String, HashCode> rootHashes;
    private HashCode hash;

    public static FileCollectionSnapshot from(Iterable<PhysicalSnapshot> roots, FingerprintingStrategy strategy) {
        Map<String, NormalizedFileSnapshot> snapshots = strategy.collectSnapshots(roots);
        if (snapshots.isEmpty()) {
            return EmptyFileCollectionSnapshot.INSTANCE;
        }
        final ImmutableMultimap.Builder<String, HashCode> builder = ImmutableMultimap.builder();
        final MutableBoolean hasUnknownTreeHashes = new MutableBoolean(false);
        PhysicalSnapshotVisitor rootHashesVisitor = new PhysicalSnapshotVisitor() {
            @Override
            public boolean preVisitDirectory(PhysicalDirectorySnapshot directorySnapshot) {
                HashCode treeHash = directorySnapshot.getTreeHash();
                if (treeHash == null) {
                    hasUnknownTreeHashes.set(true);
                } else {
                    builder.put(directorySnapshot.getAbsolutePath(), treeHash);
                }
                return false;
            }

            @Override
            public void visit(PhysicalSnapshot fileSnapshot) {
                builder.put(fileSnapshot.getAbsolutePath(), fileSnapshot.getContentHash());
            }

            @Override
            public void postVisitDirectory() {
            }
        };
        for (PhysicalSnapshot root : roots) {
            if (root instanceof FilteredPhysicalSnapshot) {
                hasUnknownTreeHashes.set(true);
            } else {
                root.accept(rootHashesVisitor);
            }
        }
        ImmutableMultimap<String, HashCode> rootHashes = hasUnknownTreeHashes.get() ? null : builder.build();
        return new DefaultFileCollectionFingerprint(snapshots, strategy.getCompareStrategy(), roots, null, rootHashes);
    }

    public DefaultFileCollectionFingerprint(Map<String, NormalizedFileSnapshot> snapshots, FingerprintCompareStrategy strategy, @Nullable HashCode hash, @Nullable Multimap<String, HashCode> rootHashes) {
        this(snapshots, strategy, null, hash, rootHashes);
    }

    private DefaultFileCollectionFingerprint(Map<String, NormalizedFileSnapshot> snapshots, FingerprintCompareStrategy strategy, @Nullable final Iterable<PhysicalSnapshot> roots, @Nullable HashCode hash, @Nullable Multimap<String, HashCode> rootHashes) {
        this.snapshots = snapshots;
        this.strategy = strategy;
        this.roots = roots;
        this.hash = hash;
        this.rootHashes = rootHashes;
    }

    @Override
    public boolean visitChangesSince(FileCollectionSnapshot oldSnapshot, String title, boolean includeAdded, TaskStateChangeVisitor visitor) {
        if (hasSameRootHashes(oldSnapshot)) {
            return true;
        }
        return strategy.visitChangesSince(visitor, getSnapshots(), oldSnapshot.getSnapshots(), title, includeAdded);
    }

    private boolean hasSameRootHashes(FileCollectionSnapshot oldSnapshot) {
        if (rootHashes != null && oldSnapshot instanceof DefaultFileCollectionFingerprint) {
            DefaultFileCollectionFingerprint oldFingerprint = (DefaultFileCollectionFingerprint) oldSnapshot;
            if (oldFingerprint.rootHashes != null) {
                List<String> currentRootPaths = Lists.newArrayList(rootHashes.keys());
                List<String> oldRootPaths = Lists.newArrayList(oldFingerprint.rootHashes.keys());
                return currentRootPaths.equals(oldRootPaths) && rootHashes.equals(oldFingerprint.rootHashes);
            }
        }
        return false;
    }

    @Override
    public HashCode getHash() {
        if (hash == null) {
            DefaultBuildCacheHasher hasher = new DefaultBuildCacheHasher();
            strategy.appendToHasher(hasher, snapshots);
            hash = hasher.hash();
        }
        return hash;
    }

    @Override
    public Map<String, NormalizedFileSnapshot> getSnapshots() {
        return snapshots;
    }

    @Override
    public void visitRoots(PhysicalSnapshotVisitor visitor) {
        if (roots == null) {
            throw new UnsupportedOperationException("Roots not available.");
        }
        for (PhysicalSnapshot root : roots) {
            root.accept(visitor);
        }
    }

    @Override
    public void appendToHasher(BuildCacheHasher hasher) {
        hasher.putHash(getHash());
    }

    @VisibleForTesting
    FingerprintCompareStrategy getStrategy() {
        return strategy;
    }

    @VisibleForTesting
    @Nullable
    Multimap<String, HashCode> getRootHashes() {
        return rootHashes;
    }

    public static class SerializerImpl implements Serializer<DefaultFileCollectionFingerprint> {

        private final HashCodeSerializer hashCodeSerializer;
        private final SnapshotMapSerializer snapshotMapSerializer;
        private final StringInterner stringInterner;

        public SerializerImpl(StringInterner stringInterner) {
            this.hashCodeSerializer = new HashCodeSerializer();
            this.snapshotMapSerializer = new SnapshotMapSerializer(stringInterner);
            this.stringInterner = stringInterner;
        }

        @Override
        public DefaultFileCollectionFingerprint read(Decoder decoder) throws IOException {
            int type = decoder.readSmallInt();
            FingerprintCompareStrategy compareStrategy = FingerprintCompareStrategy.values()[type];
            boolean hasHash = decoder.readBoolean();
            HashCode hash = hasHash ? hashCodeSerializer.read(decoder) : null;
            Map<String, NormalizedFileSnapshot> snapshots = snapshotMapSerializer.read(decoder);
            ImmutableMultimap<String, HashCode> rootHashes = readRootHashes(decoder);
            return new DefaultFileCollectionFingerprint(snapshots, compareStrategy, hash, rootHashes);
        }

        @Override
        public void write(Encoder encoder, DefaultFileCollectionFingerprint value) throws Exception {
            encoder.writeSmallInt(value.strategy.ordinal());
            encoder.writeBoolean(value.hash != null);
            if (value.hash != null) {
                hashCodeSerializer.write(encoder, value.getHash());
            }
            snapshotMapSerializer.write(encoder, value.getSnapshots());
            writeRootHashes(encoder, value.rootHashes);
        }

        @Nullable
        private ImmutableMultimap<String, HashCode> readRootHashes(Decoder decoder) throws IOException {
            int numberOfRoots = decoder.readSmallInt();
            if (numberOfRoots == 0) {
                return null;
            }
            ImmutableMultimap.Builder<String, HashCode> builder = ImmutableMultimap.builder();
            for (int i = 0; i < numberOfRoots; i++) {
                String absolutePath = stringInterner.intern(decoder.readString());
                HashCode rootHash = hashCodeSerializer.read(decoder);
                builder.put(absolutePath, rootHash);
            }
            return builder.build();
        }

        private void writeRootHashes(Encoder encoder, @Nullable Multimap<String, HashCode> rootHashes) throws IOException {
            if (rootHashes == null || rootHashes.isEmpty()) {
                encoder.writeSmallInt(0);
                return;
            }
            encoder.writeSmallInt(rootHashes.size());
            for (Map.Entry<String, HashCode> entry : rootHashes.entries()) {
                encoder.writeString(entry.getKey());
                hashCodeSerializer.write(encoder, entry.getValue());
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }

            DefaultFileCollectionFingerprint.SerializerImpl rhs = (DefaultFileCollectionFingerprint.SerializerImpl) obj;
            return Objects.equal(snapshotMapSerializer, rhs.snapshotMapSerializer)
                && Objects.equal(hashCodeSerializer, rhs.hashCodeSerializer);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(super.hashCode(), snapshotMapSerializer, hashCodeSerializer);
        }
    }

}
