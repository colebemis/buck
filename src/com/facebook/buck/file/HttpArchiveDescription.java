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

package com.facebook.buck.file;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.BuildTargetSourcePathToArtifactConverter;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.LegacyProviderCompatibleDescription;
import com.facebook.buck.core.rules.ProviderCreationContext;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.syntax.Dict;
import java.net.URI;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A description for downloading an archive over http and extracting it (versus the combo logic
 * contained in {@link RemoteFileDescription}.
 */
public class HttpArchiveDescription
    implements DescriptionWithTargetGraph<HttpArchiveDescriptionArg>,
        LegacyProviderCompatibleDescription<HttpArchiveDescriptionArg> {
  private static final Flavor ARCHIVE_DOWNLOAD = InternalFlavor.of("archive-download");

  @Override
  public Class<HttpArchiveDescriptionArg> getConstructorArgType() {
    return HttpArchiveDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      HttpArchiveDescriptionArg args) {

    HashCode sha256 =
        HttpCommonDescriptionArg.HttpCommonDescriptionArgHelpers.parseSha256(
            args.getSha256(), buildTarget);
    HttpCommonDescriptionArg.HttpCommonDescriptionArgHelpers.validateUris(
        args.getUrls(), buildTarget);

    // TODO(pjameson): Pull `out` from the providers once we've defaulted to compatible/RAG mode
    String out = outputName(buildTarget, args);
    ArchiveFormat format =
        args.getType()
            .map(t -> getTypeFromType(t, buildTarget))
            .orElseGet(() -> getTypeFromFilename(args.getUrls(), buildTarget));

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    // Setup the implicit download rule
    BuildTarget httpFileTarget = buildTarget.withAppendedFlavors(ARCHIVE_DOWNLOAD);
    Downloader downloader =
        context
            .getToolchainProvider()
            .getByName(
                Downloader.DEFAULT_NAME, buildTarget.getTargetConfiguration(), Downloader.class);

    HttpFile httpFile =
        new HttpFile(
            httpFileTarget,
            projectFilesystem,
            params,
            downloader,
            args.getUrls(),
            sha256,
            out,
            false);

    BuildRuleParams httpArchiveParams =
        new BuildRuleParams(
            () -> ImmutableSortedSet.of(httpFile), ImmutableSortedSet::of, ImmutableSortedSet.of());

    context.getActionGraphBuilder().computeIfAbsent(httpFileTarget, ignored -> httpFile);

    return new HttpArchive(
        buildTarget,
        projectFilesystem,
        httpArchiveParams,
        httpFile,
        out,
        format,
        args.getStripPrefix().map(Paths::get),
        args.getExcludes());
  }

  private static String outputName(BuildTarget target, HttpArchiveDescriptionArg args) {
    return args.getOut().orElse(target.getShortNameAndFlavorPostfix());
  }

  private static SourcePath outputSourcePath(
      ProjectFilesystem filesystem, BuildTarget target, HttpArchiveDescriptionArg args) {
    String outFilename = outputName(target, args);
    return ExplicitBuildTargetSourcePath.of(
        target, HttpFile.outputPath(filesystem, target, outFilename));
  }

  private ArchiveFormat getTypeFromType(String type, BuildTarget buildTarget) {
    return ArchiveFormat.getFormatFromShortName(type)
        .orElseThrow(
            () ->
                new HumanReadableException(
                    "%s is not a valid type of archive for %s. type must be one of %s",
                    type,
                    buildTarget.getUnflavoredBuildTarget().getFullyQualifiedName(),
                    Joiner.on(", ").join(ArchiveFormat.getShortNames())));
  }

  private ArchiveFormat getTypeFromFilename(ImmutableList<URI> uris, BuildTarget buildTarget) {
    for (URI uri : uris) {
      Optional<ArchiveFormat> format = ArchiveFormat.getFormatFromFilename(uri.getPath());
      if (format.isPresent()) {
        return format.get();
      }
    }
    throw new HumanReadableException(
        "Could not determine file type from urls of %s. One url must end with one of %s, or type "
            + "must be set to one of %s",
        buildTarget.getUnflavoredBuildTarget().getFullyQualifiedName(),
        Joiner.on(", ").join(ArchiveFormat.getFileExtensions()),
        Joiner.on(", ").join(ArchiveFormat.getShortNames()));
  }

  @Override
  public ProviderInfoCollection createProviders(
      ProviderCreationContext context, BuildTarget buildTarget, HttpArchiveDescriptionArg args) {
    Artifact artifact =
        BuildTargetSourcePathToArtifactConverter.convert(
            context.getProjectFilesystem(),
            outputSourcePath(context.getProjectFilesystem(), buildTarget, args));
    return ProviderInfoCollectionImpl.builder()
        .build(new ImmutableDefaultInfo(Dict.empty(), ImmutableList.of(artifact)));
  }

  /** Arguments for an http_archive rule */
  @RuleArg
  interface AbstractHttpArchiveDescriptionArg extends HttpCommonDescriptionArg {

    Optional<String> getStripPrefix();

    Optional<String> getType();

    ImmutableList<Pattern> getExcludes();
  }
}
