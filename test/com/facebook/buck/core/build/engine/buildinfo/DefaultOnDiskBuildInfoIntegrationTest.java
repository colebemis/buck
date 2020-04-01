/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.core.build.engine.buildinfo;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.build.engine.buildinfo.BuildInfo.MetadataKey;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DefaultOnDiskBuildInfoIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public ExpectedException exceptionRule = ExpectedException.none();

  public DefaultOnDiskBuildInfoIntegrationTest() {}

  private ProjectFilesystem projectFilesystem;

  private Path metadataDirectory;
  private Path filePath;
  private Path dirPath;
  private Path subDir;
  private Path emptySubDir;
  private Path fileWithinDirPath;
  private Path otherPathWithinDir;
  private Path symlinkPath;
  private Path fileViaSymlinkPath;

  private DefaultOnDiskBuildInfo onDiskBuildInfo;

  @Before
  public void setUp() throws IOException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo/bar:baz");

    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());

    Clock clock = FakeClock.doNotCare();
    BuildId buildId = new BuildId("cat");
    BuildInfoStore buildInfoStore = new SQLiteBuildInfoStore(projectFilesystem);

    BuildInfoRecorder recorder =
        new BuildInfoRecorder(
            buildTarget, projectFilesystem, buildInfoStore, clock, buildId, ImmutableMap.of());

    recorder.addBuildMetadata("build_key0", "value0");
    recorder.addBuildMetadata("build_key1", "value1");

    recorder.addMetadata("artifact_key0", "value0");
    recorder.addMetadata("artifact_key1", "value1");

    filePath = BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s/some.file");
    dirPath = BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s/some_dir");
    subDir = dirPath.resolve("sub_dir");
    emptySubDir = dirPath.resolve("empty_sub_dir");
    fileWithinDirPath = subDir.resolve("some_inner.path");
    otherPathWithinDir = subDir.resolve("other.file");
    Path symlinkedDirPath =
        BuildTargetPaths.getScratchPath(projectFilesystem, buildTarget, "%s/symlinked_dir");
    symlinkPath = BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s/symlink");
    Path fileInSymlinkedDirPath = symlinkedDirPath.resolve("file_in_symlink");
    fileViaSymlinkPath = symlinkPath.resolve("file_in_symlink");

    recorder.recordArtifact(filePath);
    recorder.recordArtifact(dirPath);
    recorder.recordArtifact(fileWithinDirPath);
    recorder.recordArtifact(symlinkPath);

    projectFilesystem.mkdirs(subDir);
    projectFilesystem.mkdirs(emptySubDir);
    projectFilesystem.createParentDirs(filePath);
    projectFilesystem.writeContentsToPath("data0", filePath);
    projectFilesystem.writeContentsToPath("data1", fileWithinDirPath);
    projectFilesystem.writeContentsToPath("data2", otherPathWithinDir);
    projectFilesystem.writeContentsToPath("other0", filePath.getParent().resolve("non.recorded"));

    projectFilesystem.mkdirs(symlinkedDirPath);
    projectFilesystem.createSymLink(
        symlinkPath, projectFilesystem.resolve(symlinkedDirPath), false);
    projectFilesystem.writeContentsToPath("data3", fileInSymlinkedDirPath);

    recorder.addMetadata(
        BuildInfo.MetadataKey.RECORDED_PATHS,
        recorder.getRecordedPaths().stream()
            .map(Object::toString)
            .collect(ImmutableList.toImmutableList()));
    recorder.addMetadata(
        MetadataKey.RECORDED_PATH_HASHES,
        ObjectMappers.WRITER.writeValueAsString(
            ImmutableMap.of(otherPathWithinDir.toString(), "random-hash")));
    ImmutableSortedSet<Path> recursivePaths =
        DefaultOnDiskBuildInfo.getRecursivePaths(recorder.getRecordedPaths(), projectFilesystem);
    long outputSize = DefaultOnDiskBuildInfo.getOutputSize(recursivePaths, projectFilesystem);
    recorder.addMetadata(MetadataKey.OUTPUT_SIZE, Long.toString(outputSize));
    recorder.addBuildMetadata(MetadataKey.ORIGIN_BUILD_ID, "build-id");

    recorder.writeMetadataToDisk(true);

    onDiskBuildInfo = new DefaultOnDiskBuildInfo(buildTarget, projectFilesystem, buildInfoStore);

    metadataDirectory =
        BuildInfo.getPathToArtifactMetadataDirectory(buildTarget, projectFilesystem);
  }

  @Test
  public void testPathsAndMetadataForArtifactAreCorrect() throws IOException {
    assertEquals(
        ImmutableSortedSet.of(
            metadataDirectory,
            metadataDirectory.resolve(MetadataKey.ARTIFACT_METADATA),
            filePath,
            dirPath,
            subDir,
            emptySubDir,
            fileWithinDirPath,
            otherPathWithinDir,
            symlinkPath,
            fileViaSymlinkPath),
        onDiskBuildInfo.getPathsForArtifact());
    assertEquals(
        ImmutableSortedMap.of(
            BuildInfo.MetadataKey.ADDITIONAL_INFO,
            "build_id=cat,timestamp=1,artifact_data=null,",
            MetadataKey.ORIGIN_BUILD_ID,
            "build-id",
            "build_key0",
            "value0",
            "build_key1",
            "value1"),
        ImmutableSortedMap.copyOf(onDiskBuildInfo.getMetadataForArtifact()));
  }

  @Test
  public void testSuccessfulValidation() throws IOException {
    onDiskBuildInfo.validateArtifact();
  }

  @Test
  public void testUnsuccessfulValidationDueToMissingRecordedPath() throws IOException {
    // Delete a path from RECORDED_PATH_HASHES which will be expected by the validation logic.
    // We're specifically not deleting a path from RECORDED_PATHS because that will throw
    // an exception much earlier when trying to compute output size, using a path from
    // RECORDED_PATH_HASHES means we're testing for additional failures.
    projectFilesystem.deleteFileAtPathIfExists(otherPathWithinDir);

    exceptionRule.expect(IllegalStateException.class);
    onDiskBuildInfo.validateArtifact();
  }
}
